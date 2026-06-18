package org.shiroumi.backtest.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.config.MatchingPolicyConfig
import org.shiroumi.backtest.config.MatchingPolicyKind
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.AgentEntryPriceFeed
import org.shiroumi.backtest.feed.DecisionFile
import org.shiroumi.backtest.feed.DecisionFileJson
import org.shiroumi.backtest.feed.InMemoryDecisionFeed
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端：固定 agent 买点价 + 固定选股，跑 BacktestScheduler（入场闸门注入 agent 买点价 + 退出管理器）。
 *
 * 验证：
 *  1. 入场仍由「选股候选 → 入场闸门」产生，且改写为 agent 限价买点（hint=LIMIT）；
 *  2. T+1 开盘按限价撮合成交，成交价不超过 agent 买点价（不存在未来函数：买点价信号日锁定）；
 *  3. 离场仍由 PositionExitManager 按 TP8/H3 触发（本例 T+2 HIGH 触及 +8% → 止盈卖出）；
 *  4. 选股候选与基准一致（target 仍只选 000001.SZ，闸门不改选股集合，只改入场价与口径）。
 */
class AgentEntryLimitEndToEndTest {

    private val signalDay = LocalDate(2024, 1, 2)  // T：选股信号日 + agent 盘后产出买点价
    private val entryDay = LocalDate(2024, 1, 3)   // T+1：执行日（开盘按限价撮合）
    private val exitDay = LocalDate(2024, 1, 4)     // T+2：HIGH 触及 +8% → TP8 止盈
    private val calendar = InMemoryTradingCalendar(listOf(signalDay, entryDay, exitDay))

    private val ts = "000001.SZ"
    private val agentEntryPrice = 9.9

    /** 选股目标：执行日 entryDay 选出 000001.SZ（与基准一致，闸门不改这一集合）。 */
    private val decisionFeed = InMemoryDecisionFeed(
        listOf(
            StrategyDecision.TargetPortfolioDecision(
                effectiveDate = entryDay,
                reason = "选股目标",
                targetWeights = mapOf(ts to 1.0),
                sentimentExposure = 1.0,
            ),
        ),
    )

    /** 行情：信号日给 low 用于退出管理器记录；执行日开盘高于买点价但盘中触达；T+2 HIGH 触及 +8%。 */
    private object Market : BacktestMarketDataFeed {
        private val ts = "000001.SZ"
        private val signalDay = LocalDate(2024, 1, 2)
        private val entryDay = LocalDate(2024, 1, 3)
        private val exitDay = LocalDate(2024, 1, 4)

        override fun marketDataFor(date: LocalDate): DailyMarketData = when (date) {
            signalDay -> DailyMarketData(
                quotes = mapOf(ts to candle(tsCode = ts, date = date, open = 10.0, high = 10.1, low = 9.8, close = 10.0)),
                preClose = mapOf(ts to 10.0),
            )
            entryDay -> DailyMarketData(
                // 开盘 10.2 高于买点价 9.9（gap +2% 未超 3% 入场上限），当日最低 9.8 <= 9.9 → 限价触达成交；
                // preClose 10.0 支撑涨跌停带（±10% → [9.0, 11.0]，买点价 9.9 在带内）
                quotes = mapOf(ts to candle(tsCode = ts, date = date, open = 10.2, high = 10.4, low = 9.8, close = 10.2)),
                preClose = mapOf(ts to 10.0),
            )
            exitDay -> DailyMarketData(
                // HIGH 11.0 >= 入场价 9.9 ×1.08 = 10.692 → 触发 TP8 止盈
                quotes = mapOf(ts to candle(tsCode = ts, date = date, open = 10.8, high = 11.0, low = 10.7, close = 10.9)),
                preClose = mapOf(ts to 10.2),
            )
            else -> DailyMarketData(quotes = emptyMap())
        }
    }

    private fun agentFeed(): AgentEntryPriceFeed {
        val dir: Path = Files.createTempDirectory("agent-entry-e2e")
        val payload = DecisionFile(
            executionDate = entryDay,
            decisions = listOf(
                StrategyDecision.TradeIntentDecision(
                    effectiveDate = entryDay,
                    reason = "agent 信号日盘后买点",
                    tsCode = ts,
                    side = Side.BUY,
                    hint = ExecutionHint.LIMIT,
                    limitPrice = agentEntryPrice,
                ),
            ),
        )
        Files.writeString(dir.resolve("$entryDay.json"), DecisionFileJson.encodeToString(payload))
        return AgentEntryPriceFeed(dir)
    }

    @Test
    fun `agent 限价买点入场 - TP8 止盈离场端到端`() {
        val exitRules = ExitRulesConfig.TP8_H3 // tp8 / H3 生产运营点
        val result = BacktestScheduler(
            config = BacktestConfig(
                startDate = signalDay,
                endDate = exitDay,
                initialCapital = Money.ofYuan(1_000_000),
                // 注意：撮合引擎按 order.hint 分派，LIMIT 单走 LimitOrderMatching，与此处全局口径无关
                matching = MatchingPolicyConfig(policy = MatchingPolicyKind.OPEN_PRICE),
            ),
            calendar = calendar,
            marketDataFeed = Market,
            decisionFeed = decisionFeed,
            exitManager = PositionExitManager(
                config = exitRules,
                calendar = calendar,
                marketDataFeed = Market,
            ),
            entryGatekeeper = EntryGatekeeper(
                config = exitRules,
                entryPriority = BacktestEntryPriority { _, _ -> 0.05 },
                calendar = calendar,
                fullPositionPerEntry = true,
                agentEntryPrices = agentFeed(),
            ),
        ).runLoop()

        val allFills = result.days.flatMap { it.fills }

        // 入场 BUY + 离场 SELL 各一笔（1 买 1 卖）
        assertEquals(listOf(Side.BUY, Side.SELL), allFills.map { it.side }, "入场 + 止盈离场各一笔")

        // 入场日的入场决策应为 agent 限价买点（候选集合不变，只改入场价/口径）
        val entryRecord = result.days.single { it.tradeDate == entryDay }
        val entryBuys = entryRecord.decisions
            .filterIsInstance<StrategyDecision.TradeIntentDecision>()
            .filter { it.side == Side.BUY }
        assertEquals(1, entryBuys.size, "闸门只入场一只，选股候选集合不变")
        assertEquals(ts, entryBuys.single().tsCode)
        assertEquals(ExecutionHint.LIMIT, entryBuys.single().hint, "入场口径为 agent 限价买点")
        assertEquals(agentEntryPrice, entryBuys.single().limitPrice)

        // 成交价不超过 agent 买点价（限价撮合封顶）
        val buyFill = allFills.single { it.side == Side.BUY }
        assertTrue(buyFill.price <= agentEntryPrice + 1e-9, "成交价不得突破 agent 买点价：${buyFill.price}")
        assertEquals(entryDay, buyFill.tradeDate, "T+1 执行日成交")

        // 离场由 PositionExitManager 在 T+2 按 TP8 触发
        val exitRecord = result.days.single { it.tradeDate == exitDay }
        val exitSell = exitRecord.decisions
            .filterIsInstance<StrategyDecision.TradeIntentDecision>()
            .single { it.side == Side.SELL }
        assertTrue(exitSell.reason.contains("止盈"), "离场原因应为 TP8 止盈：${exitSell.reason}")
        val sellFill = allFills.single { it.side == Side.SELL }
        assertEquals(exitDay, sellFill.tradeDate, "T+2 止盈离场")
    }
}

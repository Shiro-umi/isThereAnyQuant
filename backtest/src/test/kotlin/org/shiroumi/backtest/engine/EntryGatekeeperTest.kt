package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.candle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EntryGatekeeper 单测，覆盖与生产 advance 入场逻辑对齐的四条边：
 *  1. 每日入场上限 1：只入场 entryPriority 最高的 1 只
 *  2. 波动率排序：高波动优先
 *  3. 跳空过滤：跳空票被跳过且不占名额（次优候选顶上）
 *  4. 已持有跳过 + maxDailyEntries=0 全入场透传
 */
class EntryGatekeeperTest {

    private val signalDate = LocalDate(2024, 1, 2)
    private val execDate = LocalDate(2024, 1, 3)
    private val calendar = InMemoryTradingCalendar(listOf(signalDate, execDate))

    private fun target(weights: Map<String, Double>) = StrategyDecision.TargetPortfolioDecision(
        effectiveDate = execDate,
        reason = "target",
        targetWeights = weights,
        sentimentExposure = 0.5,
    )

    /** 行情：每只票给开盘价 + 信号日收盘（preClose）。 */
    private fun market(quotes: Map<String, Pair<Double, Double>>): DailyMarketData = DailyMarketData(
        quotes = quotes.mapValues { (code, ohlc) ->
            candle(tsCode = code, date = execDate, open = ohlc.first, high = ohlc.first * 1.05, low = ohlc.first * 0.97, close = ohlc.first)
        },
        // preClose = 信号日收盘价，用于跳空过滤
        preClose = quotes.mapValues { (_, ohlc) -> ohlc.second },
    )

    /** 固定波动率桩：按传入 map 返回，缺失返回 0。 */
    private fun priorityOf(vol: Map<String, Double>) = BacktestEntryPriority { code, _ -> vol[code] ?: 0.0 }

    @Test
    fun `每日入场上限1 - 只入场波动率最高的一只`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.03),
            entryPriority = priorityOf(mapOf("A.SZ" to 0.01, "B.SZ" to 0.05, "C.SZ" to 0.03)),
            calendar = calendar,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("A.SZ" to 0.33, "B.SZ" to 0.33, "C.SZ" to 0.33))),
            market = market(mapOf(
                "A.SZ" to (10.0 to 10.0),
                "B.SZ" to (20.0 to 20.0),
                "C.SZ" to (30.0 to 30.0),
            )),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size, "每日入场上限 1")
        assertEquals("B.SZ", buys[0].tsCode, "B 波动率最高")
        assertEquals(ExecutionHint.OPEN, buys[0].hint)
        // 不应再有目标组合 / 落选票卖出意图
        assertTrue(out.none { it is StrategyDecision.TargetPortfolioDecision }, "目标组合应被改写为显式 BUY")
    }

    @Test
    fun `跳空过滤 - 最高波动率票跳空被跳过且不占名额 - 次优顶上`() {
        // B 波动率最高但开盘较信号日收盘跳空 5% > 3%；C 次之且不跳空 → 入场 C
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.03),
            entryPriority = priorityOf(mapOf("A.SZ" to 0.01, "B.SZ" to 0.05, "C.SZ" to 0.03)),
            calendar = calendar,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("A.SZ" to 0.33, "B.SZ" to 0.33, "C.SZ" to 0.33))),
            market = market(mapOf(
                "A.SZ" to (10.0 to 10.0),      // 无跳空
                "B.SZ" to (21.0 to 20.0),      // 开 21 / 信号收 20 = +5% 跳空 → 跳过
                "C.SZ" to (30.0 to 30.0),      // 无跳空
            )),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size, "跳空不占名额，名额给次优候选")
        assertEquals("C.SZ", buys[0].tsCode, "B 跳空被跳过，C 顶上")
    }

    @Test
    fun `已持有标的跳过 - 入场次高波动率的未持有票`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.03),
            entryPriority = priorityOf(mapOf("A.SZ" to 0.01, "B.SZ" to 0.05, "C.SZ" to 0.03)),
            calendar = calendar,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("A.SZ" to 0.33, "B.SZ" to 0.33, "C.SZ" to 0.33))),
            market = market(mapOf(
                "A.SZ" to (10.0 to 10.0),
                "B.SZ" to (20.0 to 20.0),
                "C.SZ" to (30.0 to 30.0),
            )),
            heldTsCodes = setOf("B.SZ"),  // B 已持有
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size)
        assertEquals("C.SZ", buys[0].tsCode, "B 已持有被跳过，入场次优 C")
    }

    @Test
    fun `开盘价非正的票被跳过`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.0),
            entryPriority = priorityOf(mapOf("A.SZ" to 0.05, "B.SZ" to 0.03)),
            calendar = calendar,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("A.SZ" to 0.5, "B.SZ" to 0.5))),
            market = market(mapOf(
                "A.SZ" to (0.0 to 10.0),   // 开盘 0 → 跳过
                "B.SZ" to (20.0 to 20.0),
            )),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size)
        assertEquals("B.SZ", buys[0].tsCode)
    }

    @Test
    fun `maxDailyEntries=0 - 全入场行为原样透传目标组合`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 0, takeProfitPct = 0.05, timeStopDays = 15),
            entryPriority = priorityOf(emptyMap()),
            calendar = calendar,
        )
        val original = target(mapOf("A.SZ" to 0.5, "B.SZ" to 0.5))
        val out = gk.gate(
            date = execDate,
            decisions = listOf(original),
            market = market(mapOf("A.SZ" to (10.0 to 10.0), "B.SZ" to (20.0 to 20.0))),
            heldTsCodes = emptySet(),
        )
        assertEquals(listOf(original), out, "maxDailyEntries=0 不改写决策")
    }

    @Test
    fun `显式 SELL 决策原样透传不进入入场闸门`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1),
            entryPriority = priorityOf(mapOf("A.SZ" to 0.05)),
            calendar = calendar,
        )
        val sell = StrategyDecision.TradeIntentDecision(
            effectiveDate = execDate, reason = "exit", tsCode = "Z.SZ", side = Side.SELL,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(sell, target(mapOf("A.SZ" to 1.0))),
            market = market(mapOf("A.SZ" to (10.0 to 10.0))),
            heldTsCodes = emptySet(),
        )
        assertTrue(out.contains(sell), "SELL 应原样透传")
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size)
        assertEquals("A.SZ", buys[0].tsCode)
    }
}

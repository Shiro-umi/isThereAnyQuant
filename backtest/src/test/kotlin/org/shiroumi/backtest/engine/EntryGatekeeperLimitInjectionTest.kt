package org.shiroumi.backtest.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.AgentEntryPriceFeed
import org.shiroumi.backtest.feed.DecisionFile
import org.shiroumi.backtest.feed.DecisionFileJson
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EntryGatekeeper agent 买点价注入单测：
 *  1. 有 agent 买点价 → 输出 hint=LIMIT + limitPrice=agentPrice（替换硬写 OPEN）
 *  2. 无 agent 买点价 → 放弃该标的入场（不回退 OPEN），名额让给次优有价候选
 *  3. agentEntryPrices=null → 维持原 OPEN 行为
 */
class EntryGatekeeperLimitInjectionTest {

    private val signalDate = LocalDate(2024, 1, 2)
    private val execDate = LocalDate(2024, 1, 3)
    private val calendar = InMemoryTradingCalendar(listOf(signalDate, execDate))

    private fun target(weights: Map<String, Double>) = StrategyDecision.TargetPortfolioDecision(
        effectiveDate = execDate,
        reason = "target",
        targetWeights = weights,
        sentimentExposure = 0.5,
    )

    private fun market(quotes: Map<String, Pair<Double, Double>>): DailyMarketData = DailyMarketData(
        quotes = quotes.mapValues { (code, ohlc) ->
            candle(tsCode = code, date = execDate, open = ohlc.first, high = ohlc.first * 1.05, low = ohlc.first * 0.97, close = ohlc.first)
        },
        preClose = quotes.mapValues { (_, ohlc) -> ohlc.second },
    )

    private fun priorityOf(vol: Map<String, Double>) = BacktestEntryPriority { code, _ -> vol[code] ?: 0.0 }

    /** 写一份 agent 买点价文件，只含 BUY/LIMIT 决策。 */
    private fun agentFeed(prices: Map<String, Double>): AgentEntryPriceFeed {
        val dir: Path = Files.createTempDirectory("agent-entry-gate-test")
        val decisions = prices.map { (code, price) ->
            StrategyDecision.TradeIntentDecision(
                effectiveDate = execDate,
                reason = "agent 买点",
                tsCode = code,
                side = Side.BUY,
                hint = ExecutionHint.LIMIT,
                limitPrice = price,
            )
        }
        Files.writeString(
            dir.resolve("$execDate.json"),
            DecisionFileJson.encodeToString(DecisionFile(executionDate = execDate, decisions = decisions)),
        )
        return AgentEntryPriceFeed(dir)
    }

    @Test
    fun `有 agent 买点价 - 输出 LIMIT 与对应 limitPrice`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.0),
            entryPriority = priorityOf(mapOf("B.SZ" to 0.05, "C.SZ" to 0.03)),
            calendar = calendar,
            agentEntryPrices = agentFeed(mapOf("B.SZ" to 19.2, "C.SZ" to 29.1)),
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("B.SZ" to 0.5, "C.SZ" to 0.5))),
            market = market(mapOf("B.SZ" to (20.0 to 20.0), "C.SZ" to (30.0 to 30.0))),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size, "每日入场上限 1")
        assertEquals("B.SZ", buys[0].tsCode, "B 波动率最高")
        assertEquals(ExecutionHint.LIMIT, buys[0].hint, "应改写为 LIMIT")
        assertEquals(19.2, buys[0].limitPrice, "limitPrice 应为 agent 买点价")
    }

    @Test
    fun `无 agent 买点价 - 放弃该标的不回退 OPEN - 名额给次优有价候选`() {
        // B 波动率最高但 agent 无买点价 → 放弃；C 次之且有价 → 入场 C(LIMIT)
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.0),
            entryPriority = priorityOf(mapOf("B.SZ" to 0.05, "C.SZ" to 0.03)),
            calendar = calendar,
            agentEntryPrices = agentFeed(mapOf("C.SZ" to 29.1)), // 只有 C 有价
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("B.SZ" to 0.5, "C.SZ" to 0.5))),
            market = market(mapOf("B.SZ" to (20.0 to 20.0), "C.SZ" to (30.0 to 30.0))),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size)
        assertEquals("C.SZ", buys[0].tsCode, "B 无买点价被放弃，C 顶上")
        assertEquals(ExecutionHint.LIMIT, buys[0].hint)
        assertEquals(29.1, buys[0].limitPrice)
        assertTrue(buys.none { it.tsCode == "B.SZ" }, "B 不应以 OPEN 回退入场")
    }

    @Test
    fun `agentEntryPrices 为 null - 维持原 OPEN 行为`() {
        val gk = EntryGatekeeper(
            config = ExitRulesConfig(maxDailyEntries = 1, entryGapMaxPct = 0.0),
            entryPriority = priorityOf(mapOf("B.SZ" to 0.05)),
            calendar = calendar,
            agentEntryPrices = null,
        )
        val out = gk.gate(
            date = execDate,
            decisions = listOf(target(mapOf("B.SZ" to 1.0))),
            market = market(mapOf("B.SZ" to (20.0 to 20.0))),
            heldTsCodes = emptySet(),
        )
        val buys = out.filterIsInstance<StrategyDecision.TradeIntentDecision>().filter { it.side == Side.BUY }
        assertEquals(1, buys.size)
        assertEquals(ExecutionHint.OPEN, buys[0].hint, "无 agent 喂入维持 OPEN")
        assertEquals(null, buys[0].limitPrice)
    }
}

package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AgentEntryPriceFeed] 单测：
 *  1. 只采纳 BUY/LIMIT 决策的 limitPrice，忽略 OPEN/SELL/无价决策
 *  2. 文件缺失返回空映射
 *  3. 解析失败抛原始异常
 *  4. 同一标的多次出现保留首个买点价
 */
class AgentEntryPriceFeedTest {

    private val execDate = LocalDate(2024, 1, 3)

    private fun tempDir(): Path = Files.createTempDirectory("agent-entry-prices-test")

    private fun writeDecisionFile(dir: Path, date: LocalDate, decisions: List<StrategyDecision>) {
        val file = DecisionFile(executionDate = date, decisions = decisions)
        Files.writeString(dir.resolve("$date.json"), DecisionFileJson.encodeToString(file))
    }

    private fun limitBuy(tsCode: String, price: Double) = StrategyDecision.TradeIntentDecision(
        effectiveDate = execDate,
        reason = "agent 买点",
        tsCode = tsCode,
        side = Side.BUY,
        hint = ExecutionHint.LIMIT,
        limitPrice = price,
    )

    @Test
    fun `只采纳 BUY 且 LIMIT 且有价的买点价`() {
        val dir = tempDir()
        writeDecisionFile(
            dir, execDate,
            listOf(
                limitBuy("A.SZ", 9.5),
                limitBuy("B.SZ", 18.0),
                // OPEN hint 不采纳
                StrategyDecision.TradeIntentDecision(execDate, "open 入场", "C.SZ", Side.BUY, hint = ExecutionHint.OPEN, limitPrice = 30.0),
                // SELL 不采纳
                StrategyDecision.TradeIntentDecision(execDate, "卖出", "D.SZ", Side.SELL, hint = ExecutionHint.LIMIT, limitPrice = 12.0),
                // LIMIT 但无 limitPrice 不采纳
                StrategyDecision.TradeIntentDecision(execDate, "无价", "E.SZ", Side.BUY, hint = ExecutionHint.LIMIT, limitPrice = null),
            ),
        )
        val feed = AgentEntryPriceFeed(dir)
        val prices = feed.entryPricesFor(execDate)

        assertEquals(2, prices.size, "只有 A/B 两只 BUY+LIMIT+有价")
        assertEquals(9.5, prices["A.SZ"])
        assertEquals(18.0, prices["B.SZ"])
        assertNull(prices["C.SZ"], "OPEN hint 不采纳")
        assertNull(prices["D.SZ"], "SELL 不采纳")
        assertNull(prices["E.SZ"], "无价不采纳")
    }

    @Test
    fun `单标的查询接口返回买点价`() {
        val dir = tempDir()
        writeDecisionFile(dir, execDate, listOf(limitBuy("A.SZ", 9.5)))
        val feed = AgentEntryPriceFeed(dir)

        assertEquals(9.5, feed.entryPrice(execDate, "A.SZ"))
        assertNull(feed.entryPrice(execDate, "ZZZ.SZ"), "当日无该标的买点 → null")
    }

    @Test
    fun `文件缺失返回空映射`() {
        val dir = tempDir()
        val feed = AgentEntryPriceFeed(dir)

        assertTrue(feed.entryPricesFor(execDate).isEmpty(), "无文件时返回空映射，允许回测继续推进")
        assertNull(feed.entryPrice(execDate, "A.SZ"))
    }

    @Test
    fun `解析失败抛原始异常`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("$execDate.json"), "{ this is not valid json")
        val feed = AgentEntryPriceFeed(dir)

        assertFailsWith<Exception> { feed.entryPricesFor(execDate) }
    }

    @Test
    fun `同一标的多次出现保留首个买点价`() {
        val dir = tempDir()
        writeDecisionFile(
            dir, execDate,
            listOf(
                limitBuy("A.SZ", 9.5),
                limitBuy("A.SZ", 9.9), // 重复，应被忽略
            ),
        )
        val feed = AgentEntryPriceFeed(dir)

        assertEquals(9.5, feed.entryPrice(execDate, "A.SZ"), "保留首个买点价")
    }
}

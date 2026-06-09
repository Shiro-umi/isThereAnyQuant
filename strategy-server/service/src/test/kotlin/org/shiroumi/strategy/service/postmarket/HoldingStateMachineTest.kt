package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class HoldingStateMachineTest {

    // 连续 5 个交易日（忽略周末，便于直推交易日间隔）
    private val d1 = LocalDate(2026, 5, 11)
    private val d2 = LocalDate(2026, 5, 12)
    private val d3 = LocalDate(2026, 5, 13)
    private val d4 = LocalDate(2026, 5, 14)
    private val d5 = LocalDate(2026, 5, 15)
    private val openDates = listOf(d1, d2, d3, d4, d5)

    private val machine = HoldingStateMachine()

    private fun tradingDaysSince(entryDate: LocalDate, date: LocalDate): Int {
        if (date <= entryDate) return 0
        return openDates.count { it > entryDate && it <= date }
    }

    private fun candle(open: Float, high: Float, low: Float, close: Float) =
        Candle(
            tsCode = "X", date = d1, open = open, high = high, low = low, close = close,
            adj = 1f, volume = 100f, turnoverReal = 0f, pe = 0f, peTtm = 0f,
            pb = 0f, ps = 0f, psTtm = 0f, mvTotal = 0f, mvCirc = 0f,
        )

    @Test
    fun `T+1 禁售_入场当日不离场`() {
        // 当日新入场 A，开盘 10；即使当日 HIGH 已达 +7% 也不离场（T+1 禁售）
        val bars = mapOf("A.SZ" to candle(open = 10f, high = 11f, low = 9.9f, close = 10.5f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.holdings.map { it.tsCode })
        assertEquals(listOf("A.SZ"), result.entered)
        assertTrue(result.exited.isEmpty())
        assertEquals(10.0, result.holdings.first().entryPrice)
        assertEquals(d2, result.holdings.first().entryDate)
    }

    @Test
    fun `止盈_入场次日HIGH触及7pct离场`() {
        // A 于 d2 入场价 10；d3 HIGH 10.7 触及 +7% → 离场
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 10.2f, high = 10.7f, low = 10.1f, close = 10.6f))
        val result = machine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.exited)
        assertTrue(result.holdings.isEmpty())
    }

    @Test
    fun `时间止损_入场后第3个交易日收盘强制离场`() {
        // B 于 d2 入场价 20；d3、d4 未触发其他条件；d4 = T+3（daysSinceEntry==2）→ 时间止损
        val prior = listOf(DailyHoldingState(d3, "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0))
        // d4 HIGH 20.5 < +7%(21.4)，收盘 20.3 > 信号低 18，不触发止盈/价格止损
        val bars = mapOf("B.SZ" to candle(open = 20.2f, high = 20.5f, low = 20.0f, close = 20.3f))
        val result = machine.advance(
            tradeDate = d4,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("B.SZ"), result.exited)
        assertTrue(result.holdings.isEmpty())
    }

    @Test
    fun `价格止损_收盘跌破信号日最低离场`() {
        // C 于 d2 入场价 30，信号日低 29；d3 收盘 28.5 < 29 → 价格止损（daysSinceEntry==1）
        val prior = listOf(DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 30.0, signalDateLow = 29.0))
        val bars = mapOf("C.SZ" to candle(open = 29.5f, high = 29.8f, low = 28.0f, close = 28.5f))
        val result = machine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("C.SZ"), result.exited)
    }

    @Test
    fun `多日持有_留存与新入场并存`() {
        // d2：B 留存（入场 d1，未触发退出）；同日 A 新入场
        val prior = listOf(DailyHoldingState(d1, "B.SZ", entryDate = d1, entryPrice = 20.0, signalDateLow = 18.0))
        val bars = mapOf(
            "B.SZ" to candle(open = 20.2f, high = 20.5f, low = 20.0f, close = 20.3f), // 未触发退出
            "A.SZ" to candle(open = 10f, high = 10.3f, low = 9.9f, close = 10.1f),     // 当日入场
        )
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = prior,
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(setOf("A.SZ", "B.SZ"), result.holdings.map { it.tsCode }.toSet())
        assertEquals(listOf("A.SZ"), result.entered)
        assertTrue(result.exited.isEmpty())
        // 留存票 entryDate 不变（仍是 d1），tradeDate 更新为 d2
        val b = result.holdings.first { it.tsCode == "B.SZ" }
        assertEquals(d1, b.entryDate)
        assertEquals(d2, b.tradeDate)
    }

    @Test
    fun `已持有票不重复入场`() {
        val prior = listOf(DailyHoldingState(d1, "A.SZ", entryDate = d1, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 10.2f, high = 10.3f, low = 10.0f, close = 10.1f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = prior,
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.holdings.map { it.tsCode })
        assertTrue(result.entered.isEmpty())
        assertEquals(d1, result.holdings.first().entryDate) // 保持原入场日
    }
}

package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import model.Candle
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class HoldingStateMachineTest {

    // 连续 20 个"交易日"（自然日直推，便于直接计算交易日间隔）
    private val openDates: List<LocalDate> = buildList {
        var d = LocalDate(2026, 5, 11)
        repeat(20) {
            add(d)
            d = d.plus(1, DateTimeUnit.DAY)
        }
    }
    private val d1 = openDates[0]
    private val d2 = openDates[1]
    private val d3 = openDates[2]
    private val d4 = openDates[3]

    /** V3 默认规则：止盈 5% / 时间止损 15 交易日 / 价格止损关 / 入场跳空过滤 3%。 */
    private val machine = HoldingStateMachine()

    /** 旧版（V2 frame）规则：止盈 7% / 时间止损 3 交易日 / 价格止损开 / 无跳空过滤。 */
    private val legacyMachine = HoldingStateMachine(
        HoldingStateMachine.ExitRules(
            takeProfitPct = 0.07,
            timeStopDays = 3,
            priceStopEnabled = true,
            entryGapMaxPct = 0.0,
        )
    )

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
        // 当日新入场 A，开盘 10；即使当日 HIGH 已达止盈价也不离场（T+1 禁售）
        val bars = mapOf("A.SZ" to candle(open = 10f, high = 11f, low = 9.9f, close = 10.5f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0, signalDateClose = 9.9)),
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
    fun `止盈_入场次日HIGH触及5pct离场`() {
        // A 于 d2 入场价 10；d3 HIGH 10.5 触及 +5%（V3 默认） → 离场
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 10.2f, high = 10.5f, low = 10.1f, close = 10.4f))
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
    fun `时间止损_V3默认第15个交易日收盘强制离场`() {
        // B 于 d2 入场；第 14 个交易日（daysSinceEntry==13）未触发，第 15 个（==14）强制离场
        val bars = mapOf("B.SZ" to candle(open = 20.2f, high = 20.5f, low = 20.0f, close = 20.3f))
        val d15 = openDates[14] // daysSinceEntry = 13 → 不离场
        val d16 = openDates[15] // daysSinceEntry = 14 → 时间止损
        val holdAt15 = machine.advance(
            tradeDate = d15,
            previousHoldings = listOf(DailyHoldingState(openDates[13], "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0)),
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertTrue(holdAt15.exited.isEmpty())
        val exitAt16 = machine.advance(
            tradeDate = d16,
            previousHoldings = holdAt15.holdings,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("B.SZ"), exitAt16.exited)
    }

    @Test
    fun `时间止损_旧规则第3个交易日收盘强制离场`() {
        // 旧规则（显式配置）：B 于 d2 入场；d4 = 第 3 个交易日（daysSinceEntry==2）→ 时间止损
        val prior = listOf(DailyHoldingState(d3, "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0))
        val bars = mapOf("B.SZ" to candle(open = 20.2f, high = 20.5f, low = 20.0f, close = 20.3f))
        val result = legacyMachine.advance(
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
    fun `价格止损_旧规则收盘跌破信号日最低离场`() {
        // 旧规则（显式开启价格止损）：C 于 d2 入场，信号日低 29；d3 收盘 28.5 < 29 → 价格止损
        val prior = listOf(DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 30.0, signalDateLow = 29.0))
        val bars = mapOf("C.SZ" to candle(open = 29.5f, high = 29.8f, low = 28.0f, close = 28.5f))
        val result = legacyMachine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("C.SZ"), result.exited)
    }

    @Test
    fun `价格止损_V3默认关闭_跌破信号日最低不离场`() {
        // V3 验证结论：收盘价止损拦不住跳空深亏，反而消灭深蹲后反弹触达 → 默认关闭
        val prior = listOf(DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 30.0, signalDateLow = 29.0))
        val bars = mapOf("C.SZ" to candle(open = 29.5f, high = 29.8f, low = 28.0f, close = 28.5f))
        val result = machine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertTrue(result.exited.isEmpty())
        assertEquals(listOf("C.SZ"), result.holdings.map { it.tsCode })
    }

    @Test
    fun `入场跳空过滤_开盘较信号日收盘跳空超3pct不入场`() {
        // 信号日收盘 10，今日开盘 10.4 → 跳空 +4% > 3% → 放弃入场（跳空利润属于昨日持有者）
        val bars = mapOf("A.SZ" to candle(open = 10.4f, high = 10.6f, low = 10.3f, close = 10.5f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.5, signalDateClose = 10.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertTrue(result.entered.isEmpty())
        assertTrue(result.holdings.isEmpty())
    }

    @Test
    fun `入场跳空过滤_3pct以内正常入场`() {
        // 信号日收盘 10，今日开盘 10.2 → 跳空 +2% ≤ 3% → 正常入场
        val bars = mapOf("A.SZ" to candle(open = 10.2f, high = 10.4f, low = 10.1f, close = 10.3f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.5, signalDateClose = 10.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.entered)
        assertEquals(10.2, result.holdings.first().entryPrice, 1e-6)
    }

    @Test
    fun `入场跳空过滤_信号日收盘缺失不过滤`() {
        // signalDateClose <= 0 表示缺失 → 不做跳空过滤，正常入场
        val bars = mapOf("A.SZ" to candle(open = 10.4f, high = 10.6f, low = 10.3f, close = 10.5f))
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.5, signalDateClose = 0.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.entered)
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
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0, signalDateClose = 9.95)),
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
            newEntries = listOf(HoldingStateMachine.EntryCandidate("A.SZ", signalDateLow = 9.0, signalDateClose = 10.0)),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.holdings.map { it.tsCode })
        assertTrue(result.entered.isEmpty())
        assertEquals(d1, result.holdings.first().entryDate) // 保持原入场日
    }
}

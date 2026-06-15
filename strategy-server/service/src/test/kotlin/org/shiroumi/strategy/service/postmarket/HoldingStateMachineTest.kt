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

    /** v5 快线旧运营点（影子/回滚预设）：止盈 7% / 时间止损 5 交易日 / 阶梯 1~4 日 2.5% / 每日入场上限 1。 */
    private val machine = HoldingStateMachine(HoldingStateMachine.ExitRules.V5_FAST_TP7_H5)

    /** 现行生产默认 = tp8/H3/3 只：止盈 8% / 时间止损 3 交易日 / 阶梯 1~2 日 2.5% / 无浅止损 / 每日入场上限 3。 */
    private val prodMachine = HoldingStateMachine()

    /** 含浅止损 -3% 的状态机（浅止损已非生产默认，需显式开启验证其行为）。 */
    private val shallowMachine = HoldingStateMachine(HoldingStateMachine.ExitRules(shallowStopLossPct = -0.03))

    /** V3 运营点（回滚通道）：止盈 5% / 时间止损 15 交易日 / 无阶梯 / 全入场。 */
    private val v3Machine = HoldingStateMachine(HoldingStateMachine.ExitRules.V3_OPERATING_POINT)

    /** 旧版（V2 frame）规则：止盈 7% / 时间止损 3 交易日 / 价格止损开 / 无跳空过滤。 */
    private val legacyMachine = HoldingStateMachine(
        HoldingStateMachine.ExitRules(
            takeProfitPct = 0.07,
            timeStopDays = 3,
            priceStopEnabled = true,
            entryGapMaxPct = 0.0,
            profitProtectLadder = emptyMap(),
            maxDailyEntries = 0,
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
    fun `生产默认运营点_tp8_H3_3只参数`() {
        val rules = HoldingStateMachine.ExitRules()
        assertEquals(0.08, rules.takeProfitPct)
        assertEquals(3, rules.timeStopDays)
        assertEquals(false, rules.priceStopEnabled)
        assertEquals(0.0, rules.shallowStopLossPct) // 浅止损默认关闭（3 只分散口径）
        assertEquals(0.03, rules.entryGapMaxPct)
        assertEquals(mapOf(1 to 0.025, 2 to 0.025), rules.profitProtectLadder)
        assertEquals(3, rules.maxDailyEntries) // 每日入场前 3 只（模型分降序，各 1/N 等权）
    }

    @Test
    fun `影子预设_v5快线旧运营点参数_无浅止损`() {
        val rules = HoldingStateMachine.ExitRules.V5_FAST_TP7_H5
        assertEquals(0.07, rules.takeProfitPct)
        assertEquals(5, rules.timeStopDays)
        assertEquals(0.0, rules.shallowStopLossPct)
        assertEquals(mapOf(1 to 0.025, 2 to 0.025, 3 to 0.025, 4 to 0.025), rules.profitProtectLadder)
        assertEquals(1, rules.maxDailyEntries)
    }

    @Test
    fun `止盈_tp8默认入场次日HIGH触及8pct离场_7pct不触发止盈但触发阶梯`() {
        // A 于 d2 入场价 10；d3 HIGH 10.8 触及 +8% → TAKE_PROFIT（max(开盘,10.8)=10.8）
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val tpBar = mapOf("A.SZ" to candle(open = 10.2f, high = 10.8f, low = 10.1f, close = 10.6f))
        val verdict = prodMachine.evaluateExit(prior.first(), d3, tpBar.getValue("A.SZ"), ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.TAKE_PROFIT, verdict?.reason)
        assertEquals(10.8, verdict!!.exitPrice, 1e-6)
        // HIGH 10.7 未及 +8% → 落入 2.5% 阶梯（PROFIT_PROTECT @10.25）
        val ladderBar = candle(open = 10.1f, high = 10.7f, low = 10.0f, close = 10.5f)
        val verdict2 = prodMachine.evaluateExit(prior.first(), d3, ladderBar, ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.PROFIT_PROTECT, verdict2?.reason)
        assertEquals(10.25, verdict2!!.exitPrice, 1e-6)
    }

    @Test
    fun `时间止损_tp8默认第3个交易日收盘强平`() {
        // B 于 d2 入场；d4（daysSinceEntry=2 = timeStopDays-1）收盘强平
        val prior = listOf(DailyHoldingState(d3, "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0))
        val bar = candle(open = 19.8f, high = 20.0f, low = 19.5f, close = 19.7f)
        val verdict = prodMachine.evaluateExit(prior.first(), d4, bar, ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.TIME_STOP, verdict?.reason)
        assertEquals(19.7, verdict!!.exitPrice, 1e-4)
        // d3（daysSinceEntry=1）同样平淡的 K 线不触发时间止损
        val verdictEarly = prodMachine.evaluateExit(
            DailyHoldingState(d2, "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0),
            d3, bar, ::tradingDaysSince,
        )
        assertEquals(null, verdictEarly)
    }

    @Test
    fun `浅浮亏止损_到期前收盘跌破负3pct当日收盘离场`() {
        // C 于 d2 入场价 10；d3（daysSinceEntry=1，到期前）收盘 9.6（-4%）跌破 -3% 线 9.7 → 当日收盘离场
        val holding = DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0)
        val stopBar = candle(open = 9.9f, high = 9.95f, low = 9.5f, close = 9.6f)
        val verdict = shallowMachine.evaluateExit(holding, d3, stopBar, ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.SHALLOW_STOP, verdict?.reason)
        assertEquals(9.6, verdict!!.exitPrice, 1e-6)
    }

    @Test
    fun `浅浮亏止损_收盘浮亏未达负3pct不触发继续持有`() {
        // 收盘 9.8（-2%）未跌破 -3% 线 9.7 → 不触发（噪声浮亏不砍）
        val holding = DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0)
        val mildBar = candle(open = 9.95f, high = 10.0f, low = 9.7f, close = 9.8f)
        val verdict = shallowMachine.evaluateExit(holding, d3, mildBar, ::tradingDaysSince)
        assertEquals(null, verdict)
    }

    @Test
    fun `浅浮亏止损_止盈优先于浅止损_当日冲高后收盘深跌仍按止盈`() {
        // HIGH 10.8 先触 +8% 止盈，即便收盘 9.5 深跌 → 仍 TAKE_PROFIT（优先级在浅止损之上，不错杀）
        val holding = DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0)
        val whipsawBar = candle(open = 10.2f, high = 10.8f, low = 9.4f, close = 9.5f)
        val verdict = shallowMachine.evaluateExit(holding, d3, whipsawBar, ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.TAKE_PROFIT, verdict?.reason)
        assertEquals(10.8, verdict!!.exitPrice, 1e-6)
    }

    @Test
    fun `浅浮亏止损_到期日不走浅止损而走时间止损`() {
        // d4（daysSinceEntry=2 = timeStopDays-1，到期日）收盘 9.6 深跌：浅止损条件 days<timeStop-1 不满足 → TIME_STOP
        val holding = DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0)
        val stopBar = candle(open = 9.9f, high = 9.95f, low = 9.5f, close = 9.6f)
        val verdict = prodMachine.evaluateExit(holding, d4, stopBar, ::tradingDaysSince)
        assertEquals(HoldingStateMachine.ExitReason.TIME_STOP, verdict?.reason)
        assertEquals(9.6, verdict!!.exitPrice, 1e-6)
    }

    @Test
    fun `浅浮亏止损_v5影子预设关闭_深跌扛到到期`() {
        // V5_FAST_TP7_H5（shallowStopLossPct=0）下 d3 收盘 9.6 深跌不触发浅止损 → 继续持有（H5 未到期）
        val holding = DailyHoldingState(d2, "C.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0)
        val stopBar = candle(open = 9.9f, high = 9.95f, low = 9.5f, close = 9.6f)
        val verdict = machine.evaluateExit(holding, d3, stopBar, ::tradingDaysSince)
        assertEquals(null, verdict)
    }

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
    fun `止盈_V3规则入场次日HIGH触及5pct离场`() {
        // A 于 d2 入场价 10；d3 HIGH 10.5 触及 +5%（V3 运营点） → 离场
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 10.2f, high = 10.5f, low = 10.1f, close = 10.4f))
        val result = v3Machine.advance(
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
    fun `止盈_快线默认入场次日HIGH触及7pct离场`() {
        // A 于 d2 入场价 10；d3 HIGH 10.7 触及 +7%（快线止盈，优先级高于 2.5% 阶梯，结果同为离场）
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
    }

    @Test
    fun `时间止损_V3规则第15个交易日收盘强制离场`() {
        // B 于 d2 入场；第 14 个交易日（daysSinceEntry==13）未触发，第 15 个（==14）强制离场
        val bars = mapOf("B.SZ" to candle(open = 20.2f, high = 20.5f, low = 20.0f, close = 20.3f))
        val d15 = openDates[14] // daysSinceEntry = 13 → 不离场
        val d16 = openDates[15] // daysSinceEntry = 14 → 时间止损
        val holdAt15 = v3Machine.advance(
            tradeDate = d15,
            previousHoldings = listOf(DailyHoldingState(openDates[13], "B.SZ", entryDate = d2, entryPrice = 20.0, signalDateLow = 18.0)),
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertTrue(holdAt15.exited.isEmpty())
        val exitAt16 = v3Machine.advance(
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
    fun `价格止损_默认关闭_跌破信号日最低不离场`() {
        // 验证结论：收盘价止损拦不住跳空深亏，反而消灭深蹲后反弹触达 → V3/快线默认均关闭
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
        // d2：B 留存（入场 d1，HIGH 未触 2.5% 阶梯档）；同日 A 新入场
        val prior = listOf(DailyHoldingState(d1, "B.SZ", entryDate = d1, entryPrice = 20.0, signalDateLow = 18.0))
        val bars = mapOf(
            "B.SZ" to candle(open = 20.2f, high = 20.3f, low = 20.0f, close = 20.25f), // HIGH 20.3 < 20.5 阶梯档
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
    fun `保盈阶梯_HIGH触及档位离场_未触及不离场`() {
        // A 于 d2 入场价 10；d3（daysSinceEntry=1）HIGH 10.20 < 10.25 → 不离场；d4 HIGH 10.26 ≥ +2.5% → 离场
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val holdBars = mapOf("A.SZ" to candle(open = 10.0f, high = 10.20f, low = 9.9f, close = 10.0f))
        val held = machine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> holdBars[code] },
        )
        assertTrue(held.exited.isEmpty())
        val exitBars = mapOf("A.SZ" to candle(open = 10.0f, high = 10.26f, low = 9.9f, close = 10.1f))
        val exited = machine.advance(
            tradeDate = d4,
            previousHoldings = held.holdings,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> exitBars[code] },
        )
        assertEquals(listOf("A.SZ"), exited.exited)
    }

    @Test
    fun `保盈阶梯_V3规则阶梯关闭_HIGH2点6pct不离场`() {
        // V3 运营点（阶梯关闭）：HIGH +2.6% 不触发任何离场
        val prior = listOf(DailyHoldingState(d2, "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 10.0f, high = 10.26f, low = 9.9f, close = 10.1f))
        val result = v3Machine.advance(
            tradeDate = d3,
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertTrue(result.exited.isEmpty())
    }

    @Test
    fun `保盈阶梯_时间止损v5第5交易日强平`() {
        // 入场 d2；第 5 个交易日（daysSinceEntry=4）即便 HIGH 平淡也强制离场
        val prior = listOf(DailyHoldingState(openDates[4], "A.SZ", entryDate = d2, entryPrice = 10.0, signalDateLow = 9.0))
        val bars = mapOf("A.SZ" to candle(open = 9.8f, high = 9.9f, low = 9.7f, close = 9.8f))
        val result = machine.advance(
            tradeDate = openDates[5], // daysSinceEntry = 4 = timeStopDays-1
            previousHoldings = prior,
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.exited)
    }

    @Test
    fun `每日入场上限_按entryPriority挑1只`() {
        // 三只候选全部可入场，entryPriority: B(0.05) > A(0.03) > C(0.01) → 仅 B 入场
        val bars = mapOf(
            "A.SZ" to candle(open = 10f, high = 10.1f, low = 9.9f, close = 10f),
            "B.SZ" to candle(open = 20f, high = 20.1f, low = 19.9f, close = 20f),
            "C.SZ" to candle(open = 30f, high = 30.1f, low = 29.9f, close = 30f),
        )
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(
                HoldingStateMachine.EntryCandidate("A.SZ", 9.0, 10.0, entryPriority = 0.03),
                HoldingStateMachine.EntryCandidate("B.SZ", 19.0, 20.0, entryPriority = 0.05),
                HoldingStateMachine.EntryCandidate("C.SZ", 29.0, 30.0, entryPriority = 0.01),
            ),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("B.SZ"), result.entered)
        assertEquals(listOf("B.SZ"), result.holdings.map { it.tsCode })
    }

    @Test
    fun `每日入场上限_首选跳空被滤后顺延次选不浪费名额`() {
        // B 优先级最高但跳空 +4% 被过滤 → 名额顺延给 A
        val bars = mapOf(
            "A.SZ" to candle(open = 10f, high = 10.1f, low = 9.9f, close = 10f),
            "B.SZ" to candle(open = 20.8f, high = 21f, low = 20.7f, close = 20.9f), // 信号收盘20 → 跳空+4%
        )
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(
                HoldingStateMachine.EntryCandidate("A.SZ", 9.0, 10.0, entryPriority = 0.03),
                HoldingStateMachine.EntryCandidate("B.SZ", 19.0, 20.0, entryPriority = 0.05),
            ),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(listOf("A.SZ"), result.entered)
    }

    @Test
    fun `每日入场上限_V3规则关闭时全部入场`() {
        val bars = mapOf(
            "A.SZ" to candle(open = 10f, high = 10.1f, low = 9.9f, close = 10f),
            "B.SZ" to candle(open = 20f, high = 20.1f, low = 19.9f, close = 20f),
        )
        val result = v3Machine.advance(
            tradeDate = d2,
            previousHoldings = emptyList(),
            newEntries = listOf(
                HoldingStateMachine.EntryCandidate("A.SZ", 9.0, 10.0),
                HoldingStateMachine.EntryCandidate("B.SZ", 19.0, 20.0),
            ),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { code, _ -> bars[code] },
        )
        assertEquals(setOf("A.SZ", "B.SZ"), result.entered.toSet())
    }

    @Test
    fun `已持有票不重复入场`() {
        val prior = listOf(DailyHoldingState(d1, "A.SZ", entryDate = d1, entryPrice = 10.0, signalDateLow = 9.0))
        // HIGH 10.2 < 10.25 阶梯档 → 留存；A 同时出现在候选中也不重复入场
        val bars = mapOf("A.SZ" to candle(open = 10.1f, high = 10.2f, low = 10.0f, close = 10.1f))
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

    // ===== evaluateExit 判决（原因 + 规则口径离场价），持仓跟踪展示链路共用 =====

    private val holdingAt10 = DailyHoldingState(d1, "A.SZ", entryDate = d1, entryPrice = 10.0, signalDateLow = 9.0)

    @Test
    fun `判决_止盈触价_离场价为触发价`() {
        // HIGH 10.8 触及 10.7 止盈价，开盘 10.1 未越过 → 按触发价 10.7 离场
        val verdict = machine.evaluateExit(
            holdingAt10, d2,
            candle(open = 10.1f, high = 10.8f, low = 10.0f, close = 10.2f),
            ::tradingDaysSince,
        )
        assertEquals(HoldingStateMachine.ExitReason.TAKE_PROFIT, verdict?.reason)
        assertEquals(10.7, verdict!!.exitPrice, absoluteTolerance = 1e-6)
    }

    @Test
    fun `判决_止盈高开穿越_离场价为开盘价`() {
        // 开盘 11.0 已越过止盈价 10.7 → 实际按开盘价成交
        val verdict = machine.evaluateExit(
            holdingAt10, d2,
            candle(open = 11.0f, high = 11.2f, low = 10.8f, close = 10.9f),
            ::tradingDaysSince,
        )
        assertEquals(HoldingStateMachine.ExitReason.TAKE_PROFIT, verdict?.reason)
        assertEquals(11.0, verdict!!.exitPrice, absoluteTolerance = 1e-5)
    }

    @Test
    fun `判决_保盈阶梯触价`() {
        // HIGH 10.3 触及 D1 档 10.25（未及止盈 10.7）→ 保盈离场，离场价 10.25
        val verdict = machine.evaluateExit(
            holdingAt10, d2,
            candle(open = 10.0f, high = 10.3f, low = 9.9f, close = 10.0f),
            ::tradingDaysSince,
        )
        assertEquals(HoldingStateMachine.ExitReason.PROFIT_PROTECT, verdict?.reason)
        assertEquals(10.25, verdict!!.exitPrice, absoluteTolerance = 1e-6)
    }

    @Test
    fun `判决_时间止损按收盘价`() {
        // 第 5 个交易日（daysSinceEntry=4），全程未触阶梯/止盈 → 收盘强平
        val d5 = openDates[4]
        val verdict = machine.evaluateExit(
            holdingAt10, d5,
            candle(open = 9.8f, high = 10.1f, low = 9.6f, close = 9.7f),
            ::tradingDaysSince,
        )
        assertEquals(HoldingStateMachine.ExitReason.TIME_STOP, verdict?.reason)
        assertEquals(9.7, verdict!!.exitPrice, absoluteTolerance = 1e-5)
    }

    @Test
    fun `判决_不满足任何退出条件返回null`() {
        // HIGH 10.2 < 阶梯档 10.25，未到时间止损 → 留存
        val verdict = machine.evaluateExit(
            holdingAt10, d2,
            candle(open = 10.0f, high = 10.2f, low = 9.9f, close = 10.1f),
            ::tradingDaysSince,
        )
        assertEquals(null, verdict)
    }

    @Test
    fun `判决_与advance离场结果一致`() {
        // 同一根 bar 下 advance 判定离场 ⇔ evaluateExit 非 null
        val bar = candle(open = 10.0f, high = 10.3f, low = 9.9f, close = 10.0f)
        val result = machine.advance(
            tradeDate = d2,
            previousHoldings = listOf(holdingAt10),
            newEntries = emptyList(),
            tradingDaysSince = ::tradingDaysSince,
            candleFor = { _, _ -> bar },
        )
        assertEquals(listOf("A.SZ"), result.exited)
        assertTrue(machine.evaluateExit(holdingAt10, d2, bar, ::tradingDaysSince) != null)
    }
}

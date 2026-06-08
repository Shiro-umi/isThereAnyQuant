package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.StrategyDecisionFeed
import org.shiroumi.backtest.ledger.AccountLedger
import org.shiroumi.backtest.output.SimulationResultAggregator
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestSchedulerTest {

    private val t2 = LocalDate(2024, 1, 4)

    @Test fun `runLoop 只推进交易日并跳过节假日`() {
        val recordingFeed = RecordingDecisionFeed()
        val scheduler = scheduler(
            calendar = InMemoryTradingCalendar(listOf(T1)),
            decisionFeed = recordingFeed,
            marketDataFeed = StaticMarketDataFeed(),
        )

        val result = scheduler.runLoop(T0, T1)

        assertEquals(listOf(T1), result.days.map { it.tradeDate })
        assertEquals(listOf(T1), recordingFeed.requestedDates)
    }

    @Test fun `单日按 preOpen 到 postClose 链路生成成交并入账`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        val scheduler = scheduler(
            calendar = InMemoryTradingCalendar(listOf(T1)),
            decisionFeed = StaticDecisionFeed(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = T1,
                    reason = "buy target",
                    targetWeights = mapOf("000001.SZ" to 0.1),
                    sentimentExposure = 0.1,
                )
            ),
            marketDataFeed = StaticMarketDataFeed(close = 11.0),
            ledger = ledger,
        )

        val day = scheduler.runLoop(T1, T1).days.single()

        assertEquals(DailyRunStatus.COMPLETED, day.status)
        assertEquals(1, day.fills.size)
        assertEquals(100L, ledger.totalQty("000001.SZ"))
        assertTrue(day.settlement!!.equityPoint.equity > Money.ofYuan(9_000))
    }

    @Test fun `单日异常标记 FAILED 且后续交易日继续推进`() {
        val scheduler = scheduler(
            calendar = InMemoryTradingCalendar(listOf(T0, T1, t2)),
            decisionFeed = StaticDecisionFeed(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = T1,
                    reason = "buy target",
                    targetWeights = mapOf("000001.SZ" to 0.1),
                    sentimentExposure = 0.1,
                )
            ),
            marketDataFeed = FailingOnceMarketDataFeed(failingDate = T1),
        )

        val result = scheduler.runLoop(T0, t2)

        assertEquals(listOf(DailyRunStatus.COMPLETED, DailyRunStatus.FAILED, DailyRunStatus.COMPLETED), result.days.map { it.status })
        assertEquals(T1, result.days[1].tradeDate)
        assertTrue(result.days[1].error!!.contains("boom"))
    }

    @Test fun `无决策且空仓时跳过行情读取并直接生成现金权益点`() {
        val scheduler = scheduler(
            calendar = InMemoryTradingCalendar(listOf(T0, T1)),
            decisionFeed = StaticDecisionFeed(),
            marketDataFeed = ErrorMarketDataFeed(),
        )

        val result = scheduler.runLoop(T0, T1)

        assertEquals(listOf(DailyRunStatus.COMPLETED, DailyRunStatus.COMPLETED), result.days.map { it.status })
        assertEquals(listOf(Money.ofYuan(10_000), Money.ofYuan(10_000)), result.days.map { it.settlement!!.equityPoint.equity })
    }

    @Test fun `有持仓即使无决策也读取行情做日终估值`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        ledger.apply(
            org.shiroumi.backtest.domain.Fill(
                orderId = "buy",
                tradeDate = T0,
                tsCode = "000001.SZ",
                side = org.shiroumi.backtest.domain.Side.BUY,
                quantity = 100L,
                price = 10.0,
                grossAmount = Money.ofYuan(1_000),
                commission = Money.ZERO,
                transferFee = Money.ZERO,
                stampDuty = Money.ZERO,
            )
        )
        val scheduler = scheduler(
            calendar = InMemoryTradingCalendar(listOf(T1)),
            decisionFeed = StaticDecisionFeed(),
            marketDataFeed = StaticMarketDataFeed(close = 12.0),
            ledger = ledger,
        )

        val day = scheduler.runLoop(T1, T1).days.single()

        assertEquals(Money.ofYuan(10_200), day.settlement!!.equityPoint.equity)
    }

    @Test fun `持仓标的缺少当日收盘价时沿用最近收盘价估值`() {
        val config = BacktestConfig(
            startDate = T0,
            endDate = T1,
            initialCapital = Money.ofYuan(10_000),
            slippage = SlippageConfig(basisPoints = 0),
            costs = CostModelConfig(
                commissionRate = 0.0,
                minCommission = Money.ZERO,
                transferFeeRate = 0.0,
                stampDutyRate = 0.0,
            ),
        )
        val scheduler = BacktestScheduler(
            config = config,
            calendar = InMemoryTradingCalendar(listOf(T0, T1)),
            decisionFeed = StaticDecisionFeed(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = T0,
                    reason = "buy target",
                    targetWeights = mapOf("000001.SZ" to 1.0),
                    sentimentExposure = 1.0,
                )
            ),
            marketDataFeed = MissingHeldQuoteMarketDataFeed(),
        )

        val result = SimulationResultAggregator().aggregate("missing-held-quote", scheduler.runLoop(T0, T1))

        assertEquals(listOf(Money.ofYuan(10_000), Money.ofYuan(10_000)), result.equityCurve.map { it.equity })
        assertEquals(0.0, result.metrics.maxDrawdown)
    }

    private fun scheduler(
        calendar: TradingCalendar,
        decisionFeed: StrategyDecisionFeed,
        marketDataFeed: BacktestMarketDataFeed,
        ledger: AccountLedger = AccountLedger(Money.ofYuan(10_000)),
    ): BacktestScheduler = BacktestScheduler(
        config = BacktestConfig(
            startDate = T0,
            endDate = t2,
            initialCapital = Money.ofYuan(10_000),
        ),
        calendar = calendar,
        marketDataFeed = marketDataFeed,
        decisionFeed = decisionFeed,
        ledger = ledger,
    )

    private class RecordingDecisionFeed : StrategyDecisionFeed {
        val requestedDates: MutableList<LocalDate> = mutableListOf()
        override fun decisionsFor(date: LocalDate): List<StrategyDecision> {
            requestedDates += date
            return emptyList()
        }
    }

    private class StaticDecisionFeed(private vararg val decisions: StrategyDecision) : StrategyDecisionFeed {
        override fun decisionsFor(date: LocalDate): List<StrategyDecision> =
            decisions.filter { it.effectiveDate == date }
    }

    private class StaticMarketDataFeed(
        private val close: Double = 10.0,
    ) : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData = DailyMarketData(
            quotes = mapOf("000001.SZ" to candle(date = date, open = 10.0, close = close)),
            preClose = mapOf("000001.SZ" to 10.0),
        )
    }

    private class FailingOnceMarketDataFeed(private val failingDate: LocalDate) : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData {
            if (date == failingDate) error("boom on $date")
            return DailyMarketData(
                quotes = mapOf("000001.SZ" to candle(date = date)),
                preClose = mapOf("000001.SZ" to 10.0),
            )
        }
    }

    private class ErrorMarketDataFeed : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData =
            error("market data should not be requested for empty cash-only day")
    }

    private class MissingHeldQuoteMarketDataFeed : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData =
            if (date == T0) {
                DailyMarketData(
                    quotes = mapOf("000001.SZ" to candle(date = date, open = 10.0, close = 10.0)),
                    preClose = mapOf("000001.SZ" to 10.0),
                )
            } else {
                DailyMarketData(
                    quotes = emptyMap(),
                    preClose = mapOf("000001.SZ" to 10.0),
                    suspended = setOf("000001.SZ"),
                )
            }
    }
}

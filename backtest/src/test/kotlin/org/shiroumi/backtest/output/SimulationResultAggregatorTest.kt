package org.shiroumi.backtest.output

import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.engine.BacktestScheduler
import org.shiroumi.backtest.engine.DailyMarketData
import org.shiroumi.backtest.engine.InMemoryTradingCalendar
import org.shiroumi.backtest.feed.InMemoryDecisionFeed
import org.shiroumi.backtest.ledger.AccountLedger
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationResultAggregatorTest {

    @Test fun `聚合 Scheduler 输出为 SimulationResult`() {
        val config = BacktestConfig(T0, T1, Money.ofYuan(10_000))
        val scheduler = BacktestScheduler(
            config = config,
            calendar = InMemoryTradingCalendar(listOf(T1)),
            marketDataFeed = { date ->
                DailyMarketData(
                    quotes = mapOf("000001.SZ" to candle(date = date, open = 10.0, close = 11.0)),
                    preClose = mapOf("000001.SZ" to 10.0),
                )
            },
            decisionFeed = InMemoryDecisionFeed(
                listOf(
                    StrategyDecision.TargetPortfolioDecision(
                        effectiveDate = T1,
                        reason = "target",
                        targetWeights = mapOf("000001.SZ" to 0.1),
                        sentimentExposure = 0.1,
                    )
                )
            ),
            ledger = AccountLedger(config.initialCapital),
        )

        val result = SimulationResultAggregator().aggregate("run-1", scheduler.runLoop(T1, T1))

        assertEquals("run-1", result.runId)
        assertEquals(1, result.orders.size)
        assertEquals(1, result.equityCurve.size)
        assertTrue(result.metrics.totalReturn > 0.0)
    }
}

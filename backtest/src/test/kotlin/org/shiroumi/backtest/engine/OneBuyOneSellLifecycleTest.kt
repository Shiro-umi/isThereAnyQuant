package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.InMemoryDecisionFeed
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals

class OneBuyOneSellLifecycleTest {

    @Test fun `清仓后次日再次入选可建立新 Lot`() {
        val t2 = LocalDate(2024, 1, 4)
        val t3 = LocalDate(2024, 1, 5)
        val result = BacktestScheduler(
            config = BacktestConfig(startDate = T1, endDate = t3, initialCapital = Money.ofYuan(1_000_000)),
            calendar = InMemoryTradingCalendar(listOf(T1, t2, t3)),
            marketDataFeed = StaticMarketDataFeed,
            decisionFeed = InMemoryDecisionFeed(
                listOf(
                    target(T1, mapOf("000001.SZ" to 0.2)),
                    target(t2, emptyMap()),
                    target(t3, mapOf("000001.SZ" to 0.2)),
                )
            ),
        ).runLoop()

        assertEquals(listOf(Side.BUY, Side.SELL, Side.BUY), result.days.flatMap { it.fills }.map { it.side })
    }

    private fun target(date: LocalDate, weights: Map<String, Double>) =
        StrategyDecision.TargetPortfolioDecision(
            effectiveDate = date,
            reason = "target",
            targetWeights = weights,
            sentimentExposure = weights.values.sum(),
        )

    private object StaticMarketDataFeed : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData = DailyMarketData(
            quotes = mapOf("000001.SZ" to candle(date = date, open = 10.0, close = 10.0)),
            preClose = mapOf("000001.SZ" to 10.0),
        )
    }
}

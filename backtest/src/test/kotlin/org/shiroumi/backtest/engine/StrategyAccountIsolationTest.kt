package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.StrategyDecisionFeed
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StrategyAccountIsolationTest {

    @Test fun `同一策略同一行情不同初始资金产出的权重序列完全一致`() {
        val feedA = RecordingTargetFeed()
        val feedB = RecordingTargetFeed()

        val quantityA = runWithCapital(Money.ofYuan(1_000_000), feedA)
        val quantityB = runWithCapital(Money.ofYuan(10_000_000), feedB)

        assertEquals(feedA.weightsByDate, feedB.weightsByDate)
        assertNotEquals(quantityA, quantityB)
    }

    private fun runWithCapital(capital: Money, feed: RecordingTargetFeed): Long {
        val result = BacktestScheduler(
            config = BacktestConfig(startDate = T1, endDate = T1, initialCapital = capital),
            calendar = InMemoryTradingCalendar(listOf(T1)),
            marketDataFeed = StaticMarketDataFeed,
            decisionFeed = feed,
        ).runLoop()
        return result.days.single().fills.single().quantity
    }

    private class RecordingTargetFeed : StrategyDecisionFeed {
        val weightsByDate: MutableMap<LocalDate, Map<String, Double>> = linkedMapOf()

        override fun decisionsFor(date: LocalDate): List<StrategyDecision> {
            val weights = mapOf("000001.SZ" to 0.2)
            weightsByDate[date] = weights
            return listOf(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = date,
                    reason = "fixed strategy",
                    targetWeights = weights,
                    sentimentExposure = 0.2,
                )
            )
        }
    }

    private object StaticMarketDataFeed : BacktestMarketDataFeed {
        override fun marketDataFor(date: LocalDate): DailyMarketData = DailyMarketData(
            quotes = mapOf("000001.SZ" to candle(date = date, open = 10.0, close = 10.0)),
            preClose = mapOf("000001.SZ" to 10.0),
        )
    }
}

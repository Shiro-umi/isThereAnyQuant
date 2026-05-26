package org.shiroumi.backtest.feed

import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryDecisionFeedTest {

    @Test fun `按 effectiveDate 返回对应决策`() {
        val decision = StrategyDecision.TargetPortfolioDecision(
            effectiveDate = T1,
            reason = "test",
            targetWeights = mapOf("000001.SZ" to 0.2),
            sentimentExposure = 0.2,
        )
        val feed = InMemoryDecisionFeed(listOf(decision))

        assertTrue(feed.decisionsFor(T0).isEmpty())
        assertEquals(listOf(decision), feed.decisionsFor(T1))
    }
}

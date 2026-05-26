package org.shiroumi.strategy.service

import org.shiroumi.strategy.contract.StrategyTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyServiceOwnedTopicsTest {

    @Test
    fun `service owns INTRADAY POSITIONS and POSITION_TRACKING when intraday runtime is enabled`() {
        assertEquals(
            setOf(
                StrategyTopic.INTRADAY,
                StrategyTopic.POSITIONS,
                StrategyTopic.POSITION_TRACKING
            ),
            SERVICE_OWNED_TOPICS
        )
    }

    @Test
    fun `service does not own HEALTH topic`() {
        assertFalse(StrategyTopic.HEALTH in SERVICE_OWNED_TOPICS)
    }

    @Test
    fun `each owned topic is gated symmetrically`() {
        StrategyTopic.values()
            .filter { it != StrategyTopic.HEALTH }
            .forEach { assertTrue(it in SERVICE_OWNED_TOPICS, "topic $it should be owned by strategy-service") }
    }
}

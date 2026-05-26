package org.shiroumi.server.runtime.strategy

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic

class StrategySnapshotCursorTest {
    @Test
    fun `cursor accepts lower version from a new source instance`() {
        val cursor = StrategySnapshotCursor()

        assertEquals(true, cursor.shouldAccept(envelope(source = "strategy-service-a", version = 12)))
        assertEquals(false, cursor.shouldAccept(envelope(source = "strategy-service-a", version = 12)))
        assertEquals(false, cursor.shouldAccept(envelope(source = "strategy-service-a", version = 11)))
        assertEquals(true, cursor.shouldAccept(envelope(source = "strategy-service-b", version = 1)))
        assertEquals(false, cursor.shouldAccept(envelope(source = "strategy-service-b", version = 1)))
        assertEquals(true, cursor.shouldAccept(envelope(source = "strategy-service-b", version = 2)))
    }

    private fun envelope(source: String, version: Long): StrategySnapshotEnvelope<String> =
        StrategySnapshotEnvelope(
            topic = StrategyTopic.INTRADAY,
            version = version,
            sourceInstanceId = source,
            publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            payload = "payload-$source-$version"
        )
}

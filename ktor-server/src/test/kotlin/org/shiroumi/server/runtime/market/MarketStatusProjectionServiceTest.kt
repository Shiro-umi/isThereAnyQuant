package org.shiroumi.server.runtime.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import model.candle.MarketStatus
import org.shiroumi.server.runtime.ExecutionPhaseService
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarketStatusProjectionServiceTest {

    @Test
    fun `current payload maps active trading phase to open`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val executionPhaseService = ExecutionPhaseService(
            zoneId = zoneId,
            nowProvider = { ZonedDateTime.of(2026, 4, 7, 10, 0, 0, 0, zoneId) },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        val projectionService = MarketStatusProjectionService(
            executionPhaseService = executionPhaseService,
            zoneId = zoneId,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )

        assertEquals(MarketStatus.OPEN, projectionService.currentPayload().status)
    }

    @Test
    fun `current payload maps trading break phase to closed`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val executionPhaseService = ExecutionPhaseService(
            zoneId = zoneId,
            nowProvider = { ZonedDateTime.of(2026, 4, 7, 12, 0, 0, 0, zoneId) },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        val projectionService = MarketStatusProjectionService(
            executionPhaseService = executionPhaseService,
            zoneId = zoneId,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )

        assertEquals(MarketStatus.CLOSED, projectionService.currentPayload().status)
    }
}

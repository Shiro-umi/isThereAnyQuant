package org.shiroumi.server.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import model.dataprovider.CandleKey
import model.dataprovider.DataProviderSnapshot
import model.candle.CandlePeriod
import org.shiroumi.server.dataprovider.contract.DataProvider
import org.shiroumi.server.dataprovider.registry.DataProviderRegistry

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecutionPhaseServiceTest {

    private val service = ExecutionPhaseService(zoneId = ZoneId.of("Asia/Shanghai"))

    @Test
    fun `compute active trading phase`() {
        val now = ZonedDateTime.of(2026, 4, 7, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(ExecutionPhase.TRADING_ACTIVE, service.computePhase(now))
    }

    @Test
    fun `compute trading break phase`() {
        val now = ZonedDateTime.of(2026, 4, 7, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(ExecutionPhase.TRADING_BREAK, service.computePhase(now))
    }

    @Test
    fun `compute off market phase`() {
        val now = ZonedDateTime.of(2026, 4, 7, 18, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(ExecutionPhase.OFF_MARKET, service.computePhase(now))
    }

    @Test
    fun `runtime coordinator start is idempotent`() = runTest {
        val executionPhaseService = ExecutionPhaseService(zoneId = ZoneId.of("Asia/Shanghai"))
        val registry = DataProviderRegistry()
        val provider = RecordingProvider()
        registry.register(provider)
        val coordinator = DataProviderRuntimeCoordinator(
            executionPhaseService = executionPhaseService,
            registry = registry,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )

        coordinator.start()
        coordinator.start()

        assertEquals(1, provider.received.size)
        coordinator.stop()
    }

    @Test
    fun `trading tick flow emits repeated ticks only during trading active`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val fixedNow = ZonedDateTime.of(2026, 4, 7, 10, 0, 0, 0, zoneId)
        val service = ExecutionPhaseService(
            zoneId = zoneId,
            tickIntervalMs = 1_000L,
            nowProvider = { fixedNow },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        val received = mutableListOf<Unit>()
        val collectJob = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            service.tradingTickFlow.collect {
                received += Unit
            }
        }

        runCurrent()
        service.start()
        advanceTimeBy(2_100L)
        runCurrent()

        assertEquals(3, received.take(3).size)

        collectJob.cancel()
        service.stop()
    }

    @Test
    fun `trading tick flow stays silent outside trading active`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val fixedNow = ZonedDateTime.of(2026, 4, 7, 18, 0, 0, 0, zoneId)
        val service = ExecutionPhaseService(
            zoneId = zoneId,
            tickIntervalMs = 1_000L,
            nowProvider = { fixedNow },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        )
        val received = mutableListOf<Unit>()
        val collectJob = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            service.tradingTickFlow.collect {
                received += Unit
            }
        }

        runCurrent()
        service.start()
        advanceTimeBy(2_100L)
        runCurrent()

        assertEquals(0, received.size)

        collectJob.cancel()
        service.stop()
    }

    private class RecordingProvider : DataProvider<CandleKey, String> {
        override val key: CandleKey = CandleKey("000001.SZ", CandlePeriod.DAY)
        override val snapshotFlow = MutableStateFlow<DataProviderSnapshot<String>?>(null)
        val received = mutableListOf<Pair<ExecutionPhase, UpdateCause>>()

        override suspend fun onPhaseChanged(phase: ExecutionPhase, cause: UpdateCause) {
            received += phase to cause
        }

        override suspend fun refreshHistorical(cause: UpdateCause) = Unit

        override suspend fun refreshRealtime(cause: UpdateCause) = Unit

        override suspend fun recalibrate(cause: UpdateCause) = Unit

        override suspend fun release() = Unit
    }
}

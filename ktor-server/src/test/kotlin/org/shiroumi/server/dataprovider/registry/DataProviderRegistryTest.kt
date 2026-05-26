package org.shiroumi.server.dataprovider.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import model.dataprovider.CandleKey
import model.dataprovider.DataProviderSnapshot
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import model.candle.CandlePeriod
import org.shiroumi.server.dataprovider.contract.DataProvider

class DataProviderRegistryTest {

    @Test
    fun `notify phase changed dispatches to registered providers`() = runTest {
        val provider = RecordingProvider()
        val registry = DataProviderRegistry()
        registry.register(provider)

        registry.notifyPhaseChanged(ExecutionPhase.TRADING_ACTIVE, UpdateCause.TRADING_TICK)

        assertEquals(listOf(ExecutionPhase.TRADING_ACTIVE to UpdateCause.TRADING_TICK), provider.received)
    }

    @Test
    fun `unregister releases provider lifecycle`() = runTest {
        val provider = RecordingProvider()
        val registry = DataProviderRegistry()
        registry.register(provider)

        registry.unregister(provider.key)

        assertEquals(1, provider.releaseCalls)
        assertNull(registry.find(provider.key))
    }

    @Test
    fun `notify trading tick only dispatches to realtime capable providers`() = runTest {
        val tickProvider = RecordingProvider(realtimeTickEnabled = true)
        val passiveProvider = RecordingProvider(
            keyOverride = CandleKey("000002.SZ", CandlePeriod.WEEK),
            realtimeTickEnabled = false
        )
        val registry = DataProviderRegistry()
        registry.register(tickProvider)
        registry.register(passiveProvider)

        registry.notifyTradingTick(UpdateCause.TRADING_TICK)

        assertEquals(1, tickProvider.realtimeRefreshCalls)
        assertEquals(0, passiveProvider.realtimeRefreshCalls)
    }

    private class RecordingProvider(
        private val keyOverride: CandleKey = CandleKey("000001.SZ", CandlePeriod.DAY),
        private val realtimeTickEnabled: Boolean = true
    ) : DataProvider<CandleKey, String> {
        override val key = keyOverride
        override val snapshotFlow = MutableStateFlow<DataProviderSnapshot<String>?>(null)
        val received = mutableListOf<Pair<ExecutionPhase, UpdateCause>>()
        var releaseCalls: Int = 0
        var realtimeRefreshCalls: Int = 0

        override fun supportsRealtimeTicks(): Boolean = realtimeTickEnabled

        override suspend fun onPhaseChanged(phase: ExecutionPhase, cause: UpdateCause) {
            received += phase to cause
        }

        override suspend fun refreshHistorical(cause: UpdateCause) = Unit
        override suspend fun refreshRealtime(cause: UpdateCause) {
            realtimeRefreshCalls += 1
        }
        override suspend fun recalibrate(cause: UpdateCause) = Unit
        override suspend fun release() {
            releaseCalls += 1
        }
    }
}

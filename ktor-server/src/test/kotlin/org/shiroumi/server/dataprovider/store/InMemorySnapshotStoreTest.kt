package org.shiroumi.server.dataprovider.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import model.dataprovider.CandleKey
import model.dataprovider.DataProviderSnapshot
import model.dataprovider.ExecutionPhase
import model.candle.CandlePeriod
import kotlinx.coroutines.test.runTest

class InMemorySnapshotStoreTest {

    @Test
    fun `put and get returns snapshot`() = runTest {
        val store = InMemorySnapshotStore<String>()
        val snapshot = DataProviderSnapshot(
            key = CandleKey("000001.SZ", CandlePeriod.DAY),
            phase = ExecutionPhase.OFF_MARKET,
            historical = listOf("h1"),
            merged = listOf("h1"),
            version = 1,
            updatedAt = 1
        )

        store.put(snapshot)

        assertEquals(snapshot, store.get(snapshot.key.id))
    }

    @Test
    fun `remove clears stored snapshot`() = runTest {
        val store = InMemorySnapshotStore<String>()
        val snapshot = DataProviderSnapshot(
            key = CandleKey("000001.SZ", CandlePeriod.DAY),
            phase = ExecutionPhase.OFF_MARKET,
            merged = listOf("a"),
            version = 1,
            updatedAt = 1
        )

        store.put(snapshot)
        store.remove(snapshot.key.id)

        assertNull(store.get(snapshot.key.id))
    }

    @Test
    fun `remove deletes key slot`() = runTest {
        val store = InMemorySnapshotStore<String>()
        val key = CandleKey("000001.SZ", CandlePeriod.DAY)
        val snapshot = DataProviderSnapshot(
            key = key,
            phase = ExecutionPhase.OFF_MARKET,
            merged = listOf("first"),
            version = 1,
            updatedAt = 1
        )

        store.put(snapshot)
        assertEquals(1, store.size())

        store.remove(key.id)

        assertEquals(0, store.size())
        assertNull(store.get(key.id))
    }
}

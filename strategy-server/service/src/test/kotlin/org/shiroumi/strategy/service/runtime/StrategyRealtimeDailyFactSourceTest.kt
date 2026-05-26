package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import model.Candle
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class StrategyRealtimeDailyFactSourceTest {

    @Test
    fun `strategy realtime fact source prefetches from ktor snapshot once and serves subsets from cache`() = runTest {
        val tradeDate = LocalDate(2026, 4, 30)
        val client = RecordingKtorRealtimeDailyCandleClient { tsCodes, date ->
            tsCodes.map { tsCode -> candle(tsCode, date, close = if (tsCode == "000001.SZ") 10f else 20f) }
        }
        val factSource = KtorSnapshotStrategyRealtimeDailyFactSource(
            ktorClient = client,
            clock = FixedMutableClock(Instant.parse("2026-04-30T02:00:00Z")),
            realtimeFactTtlMs = 60_000L
        )
        val tsCodes = listOf("000001.SZ", "600000.SH")

        val all = factSource.prefetch(tsCodes, tradeDate)
        val subset = factSource.load(listOf("000001.SZ"), tradeDate)
        val missing = factSource.load(listOf("000002.SZ"), tradeDate)

        assertEquals(2, all.size)
        assertEquals(listOf(tsCodes), client.requests)
        assertEquals(listOf("000001.SZ"), subset.map { it.tsCode })
        assertEquals(emptyList(), missing)
    }

    private class RecordingKtorRealtimeDailyCandleClient(
        private val fetchBlock: (List<String>, LocalDate) -> List<Candle>
    ) : KtorRealtimeDailyCandleClient {
        val requests = mutableListOf<List<String>>()

        override suspend fun loadRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate): List<Candle> {
            requests += tsCodes
            return fetchBlock(tsCodes, tradeDate)
        }
    }

    private class FixedMutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = FixedMutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant
    }

    private fun candle(tsCode: String, date: LocalDate, close: Float): Candle = Candle(
        tsCode = tsCode,
        date = date,
        open = close,
        high = close,
        low = close,
        close = close,
        adj = 0f,
        openQfq = 0f,
        closeQfq = 0f,
        highQfq = 0f,
        lowQfq = 0f,
        volume = 1f,
        volumeQfq = 0f,
        turnoverReal = 1f,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )
}

package org.shiroumi.server.dataprovider.adapter

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import model.Candle
import model.dataprovider.RealtimeDailyCandleRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import model.candle.CandlePeriod
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class CandleDataAdaptersTest {

    @Test
    fun `weekly default start date follows legacy lookback rule`() {
        val startDate = calculateDefaultPeriodicStartDate(
            limit = 500,
            period = CandlePeriod.WEEK,
            endDate = "2026-04-08"
        )

        assertEquals("2011-10-24", startDate)
    }

    @Test
    fun `monthly default start date follows legacy lookback rule`() {
        val startDate = calculateDefaultPeriodicStartDate(
            limit = 500,
            period = CandlePeriod.MONTH,
            endDate = "2026-04-08"
        )

        assertEquals("1976-09-17", startDate)
    }

    @Test
    fun `authoritative realtime loader reuses cached facts across overlapping requests`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                val candlesByCode = mapOf(
                    "000001.SZ" to candle("000001.SZ", tradeDate, 10f),
                    "000002.SZ" to candle("000002.SZ", tradeDate, 20f)
                )
                tsCodes.mapNotNull(candlesByCode::get)
            }
        )
        val adjFetcher = RecordingTradeDateAdjFactorFetcher(
            fetchBlock = { _ ->
                mapOf("000001.SZ" to 2f, "000002.SZ" to 3f)
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = adjFetcher,
            clock = clock,
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )

        val batch = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ", "000002.SZ")))
        val single = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ")))

        assertEquals(listOf(listOf("000001.SZ", "000002.SZ")), rawFetcher.requests)
        assertEquals(1, adjFetcher.requestCount)
        assertEquals(2f, batch.first { it.tsCode == "000001.SZ" }.adj)
        assertEquals(2f, single.single().adj)
        assertEquals(10f, single.single().close)
    }

    @Test
    fun `authoritative realtime loader splits rt k requests by batch size`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                tsCodes.map { tsCode -> candle(tsCode, tradeDate, 10f) }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = RecordingTradeDateAdjFactorFetcher { emptyMap() },
            clock = clock,
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )
        val tsCodes = (1..401).map { index -> "%06d.SZ".format(index) }

        val candles = loader.load(RealtimeDailyCandleRequest(tsCodes = tsCodes))

        assertEquals(401, candles.size)
        assertEquals(listOf(200, 200, 1), rawFetcher.requests.map { it.size })
        assertEquals(tsCodes.take(200), rawFetcher.requests[0])
        assertEquals(tsCodes.drop(400), rawFetcher.requests[2])
    }

    @Test
    fun `authoritative realtime loader loads wildcard market once and caches real codes`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        val wildcards = listOf("6*.SH", "3*.SZ", "688*.SH", "0*.SZ", "8*.BJ")
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                if (tsCodes == wildcards) {
                    listOf(
                        candle("000001.SZ", tradeDate, 10f),
                        candle("600000.SH", tradeDate, 20f)
                    )
                } else {
                    tsCodes.map { tsCode -> candle(tsCode, tradeDate, 99f) }
                }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = RecordingTradeDateAdjFactorFetcher { emptyMap() },
            clock = clock,
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )

        val all = loader.loadByWildcards(wildcards)
        val cachedSingle = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()

        assertEquals(2, all.size)
        assertEquals("000001.SZ", cachedSingle.tsCode)
        assertEquals(10f, cachedSingle.close)
        assertEquals(listOf(wildcards), rawFetcher.requests)
    }

    @Test
    fun `authoritative realtime loader retries missing today adj after retry interval`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                val candlesByCode = mapOf(
                    "000001.SZ" to candle("000001.SZ", tradeDate, 10f)
                )
                tsCodes.mapNotNull(candlesByCode::get)
            }
        )
        var responseIndex = 0
        val adjFetcher = RecordingTradeDateAdjFactorFetcher(
            fetchBlock = { _ ->
                responseIndex += 1
                when (responseIndex) {
                    1 -> emptyMap()
                    else -> mapOf("000001.SZ" to 2.5f)
                }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = adjFetcher,
            clock = clock,
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )

        val first = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()
        clock.advanceMillis(31_000L)
        val second = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()

        assertEquals(0f, first.adj)
        assertEquals(0f, first.closeQfq)
        assertEquals(2.5f, second.adj)
        assertEquals(10f, second.closeQfq)
        assertEquals(1, rawFetcher.requests.size)
        assertEquals(2, adjFetcher.requestCount)
    }

    @Test
    fun `authoritative realtime loader does not reuse previous trade date adj when today adj fetch fails`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-07T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                tsCodes.map { tsCode -> candle(tsCode, tradeDate, 10f) }
            }
        )
        val adjFetcher = RecordingTradeDateAdjFactorFetcher(
            fetchBlock = { tradeDate ->
                when (tradeDate) {
                    LocalDate(2026, 4, 7) -> mapOf("000001.SZ" to 3f)
                    else -> error("today adj unavailable")
                }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = adjFetcher,
            clock = clock,
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )

        val day1 = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()
        clock.setInstant(Instant.parse("2026-04-08T02:00:00Z"))
        val day2 = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()

        assertEquals(3f, day1.adj)
        assertEquals(0f, day2.adj)
        assertEquals(0f, day2.closeQfq)
    }

    @Test
    fun `authoritative realtime loader rejects expired facts when refresh fails`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        var rawFetchShouldFail = false
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                if (rawFetchShouldFail) {
                    error("rt_k failed")
                }
                tsCodes.map { tsCode -> candle(tsCode, tradeDate, 10f) }
            }
        )
        val adjFetcher = RecordingTradeDateAdjFactorFetcher(
            fetchBlock = { _ ->
                mapOf("000001.SZ" to 2f)
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = adjFetcher,
            clock = clock,
            realtimeFactTtlMs = 1_000L,
            missingAdjRetryMs = 30_000L
        )

        loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ")))
        clock.advanceMillis(1_001L)
        rawFetchShouldFail = true

        assertFailsWith<IllegalStateException> {
            loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ")))
        }
    }

    @Test
    fun `authoritative realtime loader prunes facts outside current ttl window`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                tsCodes.mapIndexed { index, tsCode ->
                    candle(tsCode, tradeDate, close = 10f + index)
                }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = RecordingTradeDateAdjFactorFetcher { emptyMap() },
            clock = clock,
            realtimeFactTtlMs = 1_000L,
            missingAdjRetryMs = 30_000L
        )

        loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ", "000002.SZ")))
        assertEquals(2, loader.cachedRealtimeFactCount())

        clock.advanceMillis(1_001L)
        loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000003.SZ")))

        assertEquals(1, loader.cachedRealtimeFactCount())
        assertEquals(
            listOf(listOf("000001.SZ", "000002.SZ"), listOf("000003.SZ")),
            rawFetcher.requests
        )
    }

    @Test
    fun `authoritative realtime loader prunes facts from previous trade date`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-08T02:00:00Z"))
        var effectiveTradeDate = LocalDate(2026, 4, 8)
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                tsCodes.map { tsCode -> candle(tsCode, tradeDate, 10f) }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = RecordingTradeDateAdjFactorFetcher { emptyMap() },
            clock = clock,
            tradeDateProvider = { effectiveTradeDate },
            realtimeFactTtlMs = 60_000L,
            missingAdjRetryMs = 30_000L
        )

        loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ", "000002.SZ")))
        assertEquals(2, loader.cachedRealtimeFactCount())

        effectiveTradeDate = LocalDate(2026, 4, 9)
        loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000003.SZ")))

        assertEquals(1, loader.cachedRealtimeFactCount())
    }

    @Test
    fun `authoritative realtime loader uses resolved latest trading date on weekend`() = runTest {
        val clock = MutableClock(Instant.parse("2026-04-11T02:00:00Z"))
        val rawFetcher = RecordingRawRealtimeDailyFactFetcher(
            fetchBlock = { tsCodes, tradeDate ->
                tsCodes.map { tsCode -> candle(tsCode, tradeDate, 10f) }
            }
        )
        val loader = AuthoritativeRealtimeDailyCandleLoader(
            rawFactFetcher = rawFetcher,
            tradeDateAdjFactorFetcher = RecordingTradeDateAdjFactorFetcher { emptyMap() },
            clock = clock,
            tradeDateProvider = { LocalDate(2026, 4, 10) }
        )

        val candle = loader.load(RealtimeDailyCandleRequest(tsCodes = listOf("000001.SZ"))).single()

        assertEquals(LocalDate(2026, 4, 10), candle.date)
    }

    private class RecordingRawRealtimeDailyFactFetcher(
        private val fetchBlock: (List<String>, LocalDate) -> List<Candle>
    ) : RawRealtimeDailyFactFetcher {
        val requests = mutableListOf<List<String>>()

        override suspend fun fetch(tsCodes: List<String>, tradeDate: LocalDate): List<Candle> {
            requests += tsCodes
            return fetchBlock(tsCodes, tradeDate)
        }
    }

    private class RecordingTradeDateAdjFactorFetcher(
        private val fetchBlock: (LocalDate) -> Map<String, Float>
    ) : TradeDateAdjFactorFetcher {
        var requestCount: Int = 0
            private set

        override suspend fun fetch(tradeDate: LocalDate): Map<String, Float> {
            requestCount += 1
            return fetchBlock(tradeDate)
        }
    }

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceMillis(deltaMs: Long) {
            currentInstant = currentInstant.plusMillis(deltaMs)
        }

        fun setInstant(nextInstant: Instant) {
            currentInstant = nextInstant
        }
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

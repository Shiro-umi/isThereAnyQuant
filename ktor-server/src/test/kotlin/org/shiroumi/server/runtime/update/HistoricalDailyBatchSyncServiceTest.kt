package org.shiroumi.server.runtime.update

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.repository.DataCompensationTaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import model.Candle
import model.dataprovider.HistoricalDailyBatchRequest
import org.shiroumi.server.dataprovider.port.RemoteHistoricalDailyBatchFetcher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class HistoricalDailyBatchSyncServiceTest {

    init {
        DataCompensationTaskRepository.deleteAll()
    }

    @Test
    fun `default sync throttling parameters follow old batch updater`() {
        val service = DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher = object : RemoteHistoricalDailyBatchFetcher {
                override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> = emptyList()
            }
        )

        assertEquals(450, service.concurrency)
        assertEquals(100, service.chunkSize)
    }

    @Test
    fun `syncPendingTradeDates persists facts before marking calendar completed`() = runTest {
        val persisted = mutableListOf<List<Candle>>()
        val marked = mutableListOf<List<LocalDate>>()
        val events = mutableListOf<String>()
        val firstDate = LocalDate(2026, 4, 7)
        val secondDate = LocalDate(2026, 4, 8)

        val service = DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher = object : RemoteHistoricalDailyBatchFetcher {
                override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> {
                    events += "fetch:${request.tradeDate}"
                    return listOf(candle("000001.SZ", request.tradeDate))
                }
            },
            shanghaiClock = fixedClock(),
            concurrency = 1,

            chunkSize = 2,
            pendingDateLoader = { listOf(firstDate, secondDate) },
            rawDailyFactPersister = {
                events += "persist"
                persisted += it
            },
            completedDateMarker = {
                events += "mark"
                marked += it
            }
        )

        val result = service.syncPendingTradeDates()

        assertEquals(
            listOf("fetch:2026-04-07", "fetch:2026-04-08", "persist", "mark"),
            events
        )
        assertEquals(1, persisted.size)
        assertEquals(listOf(firstDate, secondDate), persisted.single().map { it.date })
        assertEquals(listOf(listOf(firstDate, secondDate)), marked)
        assertEquals(listOf(firstDate, secondDate), result.completedDates)
        assertEquals(emptyList(), result.enqueuedDates)
    }

    @Test
    fun `syncPendingTradeDates enqueues failed day after tail retry and does not mark calendar completed`() = runTest {
        val marked = mutableListOf<List<LocalDate>>()
        DataCompensationTaskRepository.deleteAll()
        val service = DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher = object : RemoteHistoricalDailyBatchFetcher {
                override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> = emptyList()
            },
            compensationTaskService = DataCompensationTaskService(
                dailyFactHandler = {},
                dailyFqHandler = {},
                strategyHandler = {},
            ),
            shanghaiClock = fixedClock(),
            concurrency = 1,

            pendingDateLoader = { listOf(LocalDate(2026, 4, 7)) },
            rawDailyFactPersister = { error("空结果不应触发落库") },
            completedDateMarker = { marked += it }
        )

        val result = service.syncPendingTradeDates()
        assertEquals(emptyList(), marked)
        assertEquals(emptyList(), result.completedDates)
        assertEquals(listOf(LocalDate(2026, 4, 7)), result.enqueuedDates)
        val task = DataCompensationTaskRepository.findByTypeAndTradeDate(
            CompensationTaskType.DAILY_FACT_BY_TRADE_DATE,
            LocalDate(2026, 4, 7)
        )
        assertNotNull(task)
    }

    @Test
    fun `syncTradeDates retries failed task at tail before enqueue`() = runTest {
        val attempts = mutableMapOf<LocalDate, Int>()
        val persisted = mutableListOf<List<Candle>>()
        val marked = mutableListOf<List<LocalDate>>()
        DataCompensationTaskRepository.deleteAll()
        val service = DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher = object : RemoteHistoricalDailyBatchFetcher {
                override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> {
                    val count = (attempts[request.tradeDate] ?: 0) + 1
                    attempts[request.tradeDate] = count
                    return if (request.tradeDate == LocalDate(2026, 4, 7) && count >= 4) {
                        listOf(candle("000001.SZ", request.tradeDate))
                    } else if (request.tradeDate == LocalDate(2026, 4, 7)) {
                        emptyList()
                    } else {
                        listOf(candle("000002.SZ", request.tradeDate))
                    }
                }
            },
            compensationTaskService = DataCompensationTaskService(
                dailyFactHandler = {},
                dailyFqHandler = {},
                strategyHandler = {},
            ),
            shanghaiClock = fixedClock(),
            concurrency = 1,

            pendingDateLoader = { emptyList() },
            rawDailyFactPersister = { persisted += it },
            completedDateMarker = { marked += it }
        )

        val output = captureStdout {
            val result = service.syncTradeDates(listOf(LocalDate(2026, 4, 7), LocalDate(2026, 4, 8)))

            assertEquals(listOf(LocalDate(2026, 4, 7), LocalDate(2026, 4, 8)), result.completedDates)
            assertEquals(emptyList(), result.enqueuedDates)
        }

        assertEquals(4, attempts[LocalDate(2026, 4, 7)])
        assertEquals(1, attempts[LocalDate(2026, 4, 8)])
        assertEquals(1, persisted.size)
        assertEquals(1, marked.size)
        assertTrue(output.contains("REQUEUED_TO_TAIL | tradeDate=2026-04-07"))
        assertTrue(output.contains("RECOVERED_AFTER_TAIL_RETRY | tradeDate=2026-04-07"))
    }

    @Test
    fun `syncTradeDates logs inline retry recovery`() = runTest {
        val attempts = mutableMapOf<LocalDate, Int>()
        val service = DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher = object : RemoteHistoricalDailyBatchFetcher {
                override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> {
                    val count = (attempts[request.tradeDate] ?: 0) + 1
                    attempts[request.tradeDate] = count
                    return if (count == 1) emptyList() else listOf(candle("000001.SZ", request.tradeDate))
                }
            },
            shanghaiClock = fixedClock(),
            concurrency = 1,

            pendingDateLoader = { emptyList() },
            rawDailyFactPersister = {},
            completedDateMarker = {}
        )

        val output = captureStdout {
            val result = service.syncTradeDates(listOf(LocalDate(2026, 4, 7)))
            assertEquals(listOf(LocalDate(2026, 4, 7)), result.completedDates)
            assertEquals(emptyList(), result.enqueuedDates)
        }

        assertEquals(2, attempts[LocalDate(2026, 4, 7)])
        assertTrue(output.contains("RECOVERED_AFTER_INLINE_RETRY | tradeDate=2026-04-07, attempt=2/3"))
    }

    private inline fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        System.setOut(PrintStream(output, true, Charsets.UTF_8))
        return try {
            block()
            output.toString(Charsets.UTF_8)
        } finally {
            System.setOut(originalOut)
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-04-08T10:00:00Z"),
        ZoneId.of("Asia/Shanghai")
    )

    private fun candle(tsCode: String, date: LocalDate): Candle = Candle(
        tsCode = tsCode,
        date = date,
        open = 10f,
        high = 11f,
        low = 9f,
        close = 10.5f,
        adj = 1f,
        openQfq = 0f,
        closeQfq = 0f,
        highQfq = 0f,
        lowQfq = 0f,
        volume = 100f,
        volumeQfq = 0f,
        turnoverReal = 1000f,
        pe = 1f,
        peTtm = 1f,
        pb = 1f,
        ps = 1f,
        psTtm = 1f,
        mvTotal = 1f,
        mvCirc = 1f
    )
}

package org.shiroumi.server.runtime.update

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.shiroumi.database.stock.LimitListDRecord
import org.shiroumi.server.dataprovider.port.RemoteLimitListDFetcher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LimitListDSyncServiceTest {

    @Test
    fun `syncTradeDates skips dates before Tushare limit_list_d coverage`() = runTest {
        val fetched = mutableListOf<LocalDate>()
        val persisted = mutableListOf<LocalDate>()
        val marked = mutableListOf<List<LocalDate>>()
        val service = DefaultLimitListDSyncService(
            remoteLimitListDFetcher = object : RemoteLimitListDFetcher {
                override suspend fun fetch(tradeDate: LocalDate): List<LimitListDRecord> {
                    fetched += tradeDate
                    return emptyList()
                }
            },
            shanghaiClock = fixedClock(),
            pendingDateLoader = { emptyList() },
            limitListPersister = { tradeDate, _ -> persisted += tradeDate },
            completedDateMarker = { marked += it }
        )

        val result = service.syncTradeDates(
            listOf(
                LocalDate(2019, 12, 31),
                LocalDate(2020, 1, 2),
            )
        )

        assertEquals(listOf(LocalDate(2020, 1, 2)), fetched)
        assertEquals(listOf(LocalDate(2020, 1, 2)), persisted)
        assertEquals(listOf(listOf(LocalDate(2020, 1, 2))), marked)
        assertEquals(listOf(LocalDate(2020, 1, 2)), result.completedDates)
        assertEquals(emptyList(), result.enqueuedDates)
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-05-25T10:00:00Z"),
        ZoneId.of("Asia/Shanghai")
    )
}

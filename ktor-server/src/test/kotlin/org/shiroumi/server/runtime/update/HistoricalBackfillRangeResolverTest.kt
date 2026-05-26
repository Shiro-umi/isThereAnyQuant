package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoricalBackfillRangeResolverTest {

    @Test
    fun `auto mode starts from next trading day after last contiguous complete trade day`() {
        val resolver = HistoricalBackfillRangeResolver(
            clock = fixedClock(),
            latestTradingDateOnOrBefore = { LocalDate(2026, 4, 9) },
            findOpenDates = { from, to ->
                allOpenDates().filter { it >= from && it <= to }
            },
            findNextTradingDate = { date ->
                allOpenDates().firstOrNull { it > date }
            },
            findExistingTradeDates = { from, to ->
                allOpenDates().filter { it >= from && it <= minOf(to, LocalDate(2026, 4, 2)) }.toSet()
            },
            findDailyUpdatedTradeDates = { from, to ->
                allOpenDates().filter { it >= from && it <= minOf(to, LocalDate(2026, 4, 2)) }.toSet()
            },
            findFqUpdatedTradeDates = { from, to ->
                allOpenDates().filter { it >= from && it <= minOf(to, LocalDate(2026, 4, 1)) }.toSet()
            },
            findStrategyUpdatedTradeDates = { from, to ->
                allOpenDates().filter { it >= from && it <= minOf(to, LocalDate(2026, 3, 31)) }.toSet()
            },
        )

        val plan = resolver.resolve(
            HistoricalBackfillOptions(
                mode = HistoricalBackfillMode.AUTO,
                fromDate = null,
                toDate = null,
                resetFlags = false,
            )
        )

        assertNotNull(plan)
        assertEquals(LocalDate(2026, 4, 3), plan.daily.startDate)
        assertEquals(LocalDate(2026, 4, 9), plan.daily.endDate)
        assertEquals(listOf(LocalDate(2026, 4, 3), LocalDate(2026, 4, 7), LocalDate(2026, 4, 8), LocalDate(2026, 4, 9)), plan.daily.tradeDates)
        assertEquals(LocalDate(2026, 4, 2), plan.daily.lastContiguousDate)
        assertEquals(LocalDate(2026, 4, 2), plan.fq.startDate)
        assertEquals(listOf(LocalDate(2026, 4, 2), LocalDate(2026, 4, 3), LocalDate(2026, 4, 7), LocalDate(2026, 4, 8), LocalDate(2026, 4, 9)), plan.fq.tradeDates)
        assertEquals(LocalDate(2026, 4, 1), plan.fq.lastContiguousDate)
        assertEquals(LocalDate(2026, 4, 1), plan.strategy.startDate)
        assertEquals(listOf(LocalDate(2026, 4, 1), LocalDate(2026, 4, 2), LocalDate(2026, 4, 3), LocalDate(2026, 4, 7), LocalDate(2026, 4, 8), LocalDate(2026, 4, 9)), plan.strategy.tradeDates)
        assertEquals(LocalDate(2026, 3, 31), plan.strategy.lastContiguousDate)
        assertFalse(plan.resetFlags)
    }

    @Test
    fun `range mode clamps floor date to 2000-01-01 and resets flags by default`() {
        val floor = LocalDate(2000, 1, 3)
        val resolver = HistoricalBackfillRangeResolver(
            clock = fixedClock(),
            latestTradingDateOnOrBefore = { LocalDate(2000, 1, 6) },
            findOpenDates = { from, to ->
                listOf(floor, LocalDate(2000, 1, 4), LocalDate(2000, 1, 5), LocalDate(2000, 1, 6))
                    .filter { it >= from && it <= to }
            },
            findNextTradingDate = { date ->
                listOf(floor, LocalDate(2000, 1, 4), LocalDate(2000, 1, 5), LocalDate(2000, 1, 6)).firstOrNull { it > date }
            },
            findExistingTradeDates = { _, _ -> emptySet() },
            findDailyUpdatedTradeDates = { _, _ -> emptySet() },
            findFqUpdatedTradeDates = { _, _ -> emptySet() },
            findStrategyUpdatedTradeDates = { _, _ -> emptySet() },
        )

        val plan = resolver.resolve(
            HistoricalBackfillOptions(
                mode = HistoricalBackfillMode.RANGE,
                fromDate = LocalDate(1999, 12, 20),
                toDate = LocalDate(2000, 1, 6),
                resetFlags = true,
            )
        )

        assertNotNull(plan)
        assertEquals(floor, plan.daily.startDate)
        assertEquals(LocalDate(2000, 1, 6), plan.daily.endDate)
        assertEquals(floor, plan.fq.startDate)
        assertEquals(LocalDate(2000, 1, 6), plan.fq.endDate)
        assertEquals(floor, plan.strategy.startDate)
        assertEquals(LocalDate(2000, 1, 6), plan.strategy.endDate)
        assertTrue(plan.resetFlags)
    }

    @Test
    fun `resolver returns null when no trading days fall inside resolved range`() {
        val resolver = HistoricalBackfillRangeResolver(
            clock = fixedClock(),
            latestTradingDateOnOrBefore = { LocalDate(2026, 4, 9) },
            findOpenDates = { _, _ -> emptyList() },
            findNextTradingDate = { null },
            findExistingTradeDates = { _, _ -> emptySet() },
            findDailyUpdatedTradeDates = { _, _ -> emptySet() },
            findFqUpdatedTradeDates = { _, _ -> emptySet() },
            findStrategyUpdatedTradeDates = { _, _ -> emptySet() },
        )

        val plan = resolver.resolve(
            HistoricalBackfillOptions(
                mode = HistoricalBackfillMode.AUTO,
                fromDate = null,
                toDate = null,
                resetFlags = false,
            )
        )

        assertNull(plan)
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-04-09T02:00:00Z"),
        ZoneId.of("Asia/Shanghai")
    )

    private fun allOpenDates(): List<LocalDate> = listOf(
        LocalDate(2000, 1, 3),
        LocalDate(2000, 1, 4),
        LocalDate(2000, 1, 5),
        LocalDate(2000, 1, 6),
        LocalDate(2026, 3, 31),
        LocalDate(2026, 4, 1),
        LocalDate(2026, 4, 2),
        LocalDate(2026, 4, 3),
        LocalDate(2026, 4, 7),
        LocalDate(2026, 4, 8),
        LocalDate(2026, 4, 9),
    )
}

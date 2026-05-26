package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import org.shiroumi.server.runtime.update.HistoricalBackfillMode
import org.shiroumi.server.runtime.update.HistoricalBackfillOptions
import org.shiroumi.server.runtime.update.HistoricalBackfillPlan

class BackfillHistoricalDataTest {

    @Test
    fun `backfill entry should update calendar before resolving range on cold start`() {
        val events = mutableListOf<String>()
        val expectedPlan = HistoricalBackfillPlan(
            mode = HistoricalBackfillMode.AUTO,
            resetFlags = false,
            daily = org.shiroumi.server.runtime.update.HistoricalBackfillStage(
                startDate = LocalDate(2000, 1, 3),
                endDate = LocalDate(2000, 1, 4),
                tradeDates = listOf(LocalDate(2000, 1, 3), LocalDate(2000, 1, 4)),
                lastContiguousDate = null,
            ),
            fq = org.shiroumi.server.runtime.update.HistoricalBackfillStage(
                startDate = LocalDate(2000, 1, 3),
                endDate = LocalDate(2000, 1, 4),
                tradeDates = listOf(LocalDate(2000, 1, 3), LocalDate(2000, 1, 4)),
                lastContiguousDate = null,
            ),
            strategy = org.shiroumi.server.runtime.update.HistoricalBackfillStage(
                startDate = LocalDate(2000, 1, 3),
                endDate = LocalDate(2000, 1, 4),
                tradeDates = listOf(LocalDate(2000, 1, 3), LocalDate(2000, 1, 4)),
                lastContiguousDate = null,
            ),
        )

        runBlocking {
            runHistoricalBackfill(
                options = HistoricalBackfillOptions(
                    mode = HistoricalBackfillMode.AUTO,
                    fromDate = null,
                    toDate = null,
                    resetFlags = false,
                ),
                updateCalendarStep = { events += "calendar" },
                resolvePlan = {
                    events += "resolve"
                    expectedPlan
                },
                executePlan = { plan ->
                    events += "execute:${plan.daily.startDate}:${plan.daily.endDate}"
                },
                reconcileStatus = {
                    events += "reconcile"
                },
            )
        }

        assertEquals(
            listOf("calendar", "resolve", "execute:2000-01-03:2000-01-04", "reconcile"),
            events
        )
    }

    @Test
    fun `backfill entry should reconcile status when no range needs execution`() {
        val events = mutableListOf<String>()

        runBlocking {
            runHistoricalBackfill(
                options = HistoricalBackfillOptions(
                    mode = HistoricalBackfillMode.AUTO,
                    fromDate = null,
                    toDate = null,
                    resetFlags = false,
                ),
                updateCalendarStep = { events += "calendar" },
                resolvePlan = {
                    events += "resolve"
                    null
                },
                executePlan = {
                    events += "execute"
                },
                reconcileStatus = {
                    events += "reconcile"
                },
            )
        }

        assertEquals(
            listOf("calendar", "resolve", "reconcile"),
            events
        )
    }
}

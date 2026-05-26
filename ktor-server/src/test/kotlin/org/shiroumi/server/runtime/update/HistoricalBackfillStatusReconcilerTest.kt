package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import model.DataUpdateStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoricalBackfillStatusReconcilerTest {

    @Test
    fun `reconcile marks idle when latest expected trade date is fully prepared`() {
        var saved: DataUpdateStatus? = null
        val reconciler = HistoricalBackfillStatusReconciler(
            clock = Clock.fixed(Instant.parse("2026-04-17T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
            previousTradingDate = { LocalDate(2026, 4, 16) },
            latestTradingDateOnOrBefore = { LocalDate(2026, 4, 17) },
            isStockDailyUpdated = { it == LocalDate(2026, 4, 16) },
            isStockDailyFqUpdated = { it == LocalDate(2026, 4, 16) },
            isStrategyUpdated = { it == LocalDate(2026, 4, 16) },
            loadCurrentStatus = {
                DataUpdateStatus(
                    state = DataUpdateStatus.STATE_FAILED,
                    lastUpdateTime = 1L,
                    currentUpdateStartTime = 2L,
                    message = "更新失败"
                )
            },
            saveStatus = { saved = it },
            currentTimeMillis = { 123456789L },
        )

        reconciler.reconcile()

        assertEquals(
            DataUpdateStatus(
                state = DataUpdateStatus.STATE_IDLE,
                lastUpdateTime = 123456789L,
                message = ""
            ),
            saved
        )
    }

    @Test
    fun `reconcile keeps status untouched when latest expected trade date is still incomplete`() {
        var saved: DataUpdateStatus? = null
        val reconciler = HistoricalBackfillStatusReconciler(
            clock = Clock.fixed(Instant.parse("2026-04-17T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
            previousTradingDate = { LocalDate(2026, 4, 16) },
            latestTradingDateOnOrBefore = { LocalDate(2026, 4, 17) },
            isStockDailyUpdated = { true },
            isStockDailyFqUpdated = { true },
            isStrategyUpdated = { false },
            saveStatus = { saved = it },
        )

        reconciler.reconcile()

        assertNull(saved)
    }
}

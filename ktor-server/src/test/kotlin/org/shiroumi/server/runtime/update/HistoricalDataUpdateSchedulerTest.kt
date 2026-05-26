package org.shiroumi.server.runtime.update

import kotlinx.coroutines.test.runTest
import model.DataUpdateStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistoricalDataUpdateSchedulerTest {

    @Test
    fun `failed update preserves last successful update time`() = runTest {
        val statuses = mutableListOf<DataUpdateStatus>()
        var shouldFail = false

        val scheduler = HistoricalDataUpdateScheduler(
            orchestrator = HistoricalDataUpdateOrchestrator(
                historicalDailyBatchSyncService = object : HistoricalDailyBatchSyncService {
                    override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult {
                        if (shouldFail) error("batch sync failed")
                        return HistoricalDailyBatchSyncResult()
                    }
                },
                compensationTaskService = DataCompensationTaskService(
                    dailyFactHandler = {},
                    dailyFqHandler = {},
                    strategyHandler = {},
                ),
                updateCalendarStep = {},
                updateStockBasicStep = {},
                pendingStockDailyFqDateLoader = { emptyList() },
                executeStockDailyFqTradeDate = {},
                pendingStrategyDateLoader = { emptyList() },
                dailyStrategyPreparationStep = { _ -> StrategyRebuildResult(processedDates = emptyList()) }
            ),
            onStatusChanged = { statuses += it }
        )

        assertTrue(scheduler.triggerUpdate())
        val completedStatus = statuses.last { it.state == DataUpdateStatus.STATE_COMPLETED }
        val firstSuccessTime = assertNotNull(completedStatus.lastUpdateTime)

        shouldFail = true
        assertTrue(scheduler.triggerUpdate())

        val failedStatus = statuses.last { it.state == DataUpdateStatus.STATE_FAILED }
        assertEquals(firstSuccessTime, failedStatus.lastUpdateTime)
    }
}

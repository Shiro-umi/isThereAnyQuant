package org.shiroumi.server.runtime.update

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.repository.DataCompensationTaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoricalDataUpdateOrchestratorTest {

    @Test
    fun `execute keeps old post market step order but uses new daily batch sync service`() = runTest {
        val steps = mutableListOf<String>()
        val progressEvents = mutableListOf<Pair<String, Int>>()
        val compensationService = DataCompensationTaskService(
            dailyFactHandler = {},
            dailyFqHandler = {},
            strategyHandler = {},
        )
        var pendingStrategy = listOf(LocalDate(2026, 4, 8))

        val orchestrator = HistoricalDataUpdateOrchestrator(
            historicalDailyBatchSyncService = object : HistoricalDailyBatchSyncService {
                override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult {
                    steps += "syncDailyBatch"
                    return HistoricalDailyBatchSyncResult()
                }
            },
            compensationTaskService = compensationService,
            limitListDSyncService = object : LimitListDSyncService {
                override suspend fun syncPendingTradeDates(): LimitListDSyncResult {
                    steps += "limitListD"
                    return LimitListDSyncResult(completedDates = listOf(LocalDate(2026, 4, 7)))
                }
            },
            updateCalendarStep = { steps += "calendar" },
            updateStockBasicStep = { steps += "stockBasic" },
            pendingStockDailyFqDateLoader = { listOf(LocalDate(2026, 4, 7)) },
            executeStockDailyFqTradeDate = { steps += "dailyFq:$it" },
            pendingStrategyDateLoader = { pendingStrategy },
            dailyStrategyPreparationStep = { _ ->
                steps += "strategy"
                pendingStrategy = emptyList()
                StrategyRebuildResult(processedDates = emptyList())
            }
        )

        orchestrator.execute { step, progress ->
            progressEvents += step to progress
        }

        assertEquals(
            listOf("calendar", "stockBasic", "syncDailyBatch", "limitListD", "dailyFq:2026-04-07", "strategy"),
            steps
        )
        assertEquals(
            listOf(
                "更新交易日历" to 10,
                "更新股票基础信息" to 20,
                "更新日线数据" to 40,
                "更新涨跌停炸板数据" to 50,
                "更新开盘5min数据" to 56,
                "更新复权价格" to 62,
                "预处理策略日频数据" to 76
            ),
            progressEvents
        )
    }

    @Test
    fun `execute stops before fq when daily fact compensation still outstanding`() = runTest {
        DataCompensationTaskRepository.deleteAll()
        val compensationService = DataCompensationTaskService(
            dailyFactHandler = { throw IllegalStateException("still failing") },
            dailyFqHandler = {},
            strategyHandler = {},
        )
        compensationService.enqueueFailure(
            taskType = CompensationTaskType.DAILY_FACT_BY_TRADE_DATE,
            tradeDate = LocalDate(2026, 4, 7),
            sourceStage = "test",
            lastError = "boom"
        )
        val steps = mutableListOf<String>()
        val orchestrator = HistoricalDataUpdateOrchestrator(
            historicalDailyBatchSyncService = object : HistoricalDailyBatchSyncService {
                override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult {
                    steps += "syncDailyBatch"
                    return HistoricalDailyBatchSyncResult()
                }
            },
            compensationTaskService = compensationService,
            limitListDSyncService = object : LimitListDSyncService {
                override suspend fun syncPendingTradeDates(): LimitListDSyncResult {
                    error("should not enter limit_list_d stage")
                }
            },
            updateCalendarStep = { steps += "calendar" },
            updateStockBasicStep = { steps += "stockBasic" },
            pendingStockDailyFqDateLoader = { error("should not enter fq stage") },
            executeStockDailyFqTradeDate = { error("should not execute fq") },
            pendingStrategyDateLoader = { emptyList() },
            dailyStrategyPreparationStep = { _ -> error("should not execute strategy") }
        )

        assertFailsWith<IllegalArgumentException> {
            orchestrator.execute { _, _ -> }
        }
        assertEquals(listOf("calendar", "stockBasic", "syncDailyBatch"), steps)
    }
}

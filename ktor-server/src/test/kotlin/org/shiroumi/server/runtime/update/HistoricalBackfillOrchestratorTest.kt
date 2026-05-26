package org.shiroumi.server.runtime.update

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoricalBackfillOrchestratorTest {

    @Test
    fun `execute keeps explicit backfill order and resets flags before sync when enabled`() = runTest {
        val steps = mutableListOf<String>()
        val progressEvents = mutableListOf<Pair<String, Int>>()
        val tradeDates = listOf(LocalDate(2026, 4, 7), LocalDate(2026, 4, 8))
        val fqDates = listOf(LocalDate(2026, 4, 8))
        val strategyDates = listOf(LocalDate(2026, 4, 8))

        val orchestrator = HistoricalBackfillOrchestrator(
            historicalDailyBatchSyncService = object : HistoricalDailyBatchSyncService {
                override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult = error("not used")

                override suspend fun syncTradeDates(tradeDates: List<LocalDate>): HistoricalDailyBatchSyncResult {
                    steps += "sync:${tradeDates.joinToString(",")}"
                    return HistoricalDailyBatchSyncResult(completedDates = tradeDates)
                }
            },
            updateCalendarStep = { steps += "calendar" },
            updateStockBasicStep = { steps += "stockBasic" },
            resetStockDailyFlagsStep = { steps += "resetStock:${it.tradeDates.joinToString(",")}" },
            resetStockDailyFqFlagsStep = { steps += "resetFq:${it.tradeDates.joinToString(",")}" },
            resetStrategyFlagsStep = { steps += "resetStrategy:${it.tradeDates.joinToString(",")}" },
            updateStockDailyFqStep = {
                steps += "dailyFq:${it.joinToString(",")}"
                it
            },
            executeStrategyStep = {
                steps += "strategy:${it.joinToString(",")}"
            },
        )

        orchestrator.execute(
            plan = HistoricalBackfillPlan(
                mode = HistoricalBackfillMode.RANGE,
                resetFlags = true,
                daily = HistoricalBackfillStage(
                    startDate = tradeDates.first(),
                    endDate = tradeDates.last(),
                    tradeDates = tradeDates,
                    lastContiguousDate = LocalDate(2026, 4, 3),
                ),
                fq = HistoricalBackfillStage(
                    startDate = fqDates.first(),
                    endDate = fqDates.last(),
                    tradeDates = fqDates,
                    lastContiguousDate = LocalDate(2026, 4, 7),
                ),
                strategy = HistoricalBackfillStage(
                    startDate = strategyDates.first(),
                    endDate = strategyDates.last(),
                    tradeDates = strategyDates,
                    lastContiguousDate = LocalDate(2026, 4, 7),
                ),
            )
        ) { step, progress ->
            progressEvents += step to progress
        }

        assertEquals(
            listOf(
                "calendar",
                "stockBasic",
                "resetStock:2026-04-07,2026-04-08",
                "resetFq:2026-04-08",
                "resetStrategy:2026-04-08",
                "sync:2026-04-07,2026-04-08",
                "dailyFq:2026-04-08",
                "strategy:2026-04-08",
            ),
            steps
        )
        assertEquals(
            listOf(
                "更新交易日历" to 5,
                "更新股票基础信息" to 10,
                "重置回补区间标记" to 20,
                "追平历史日线数据" to 40,
                "更新复权价格" to 65,
                "追平滚动情绪数据" to 85,
            ),
            progressEvents
        )
    }

    @Test
    fun `execute stops when strategy stage fails`() = runTest {
        val steps = mutableListOf<String>()
        val tradeDates = listOf(LocalDate(2026, 4, 7))
        val strategyDates = listOf(LocalDate(2026, 4, 8))

        val orchestrator = HistoricalBackfillOrchestrator(
            historicalDailyBatchSyncService = object : HistoricalDailyBatchSyncService {
                override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult = error("not used")

                override suspend fun syncTradeDates(tradeDates: List<LocalDate>): HistoricalDailyBatchSyncResult {
                    steps += "sync:${tradeDates.joinToString(",")}"
                    return HistoricalDailyBatchSyncResult(completedDates = tradeDates)
                }
            },
            updateCalendarStep = { steps += "calendar" },
            updateStockBasicStep = { steps += "stockBasic" },
            updateStockDailyFqStep = {
                steps += "dailyFq:${it.joinToString(",")}"
                it
            },
            executeStrategyStep = {
                steps += "strategy:${it.joinToString(",")}"
                throw IllegalStateException("strategy failed")
            },
        )

        assertFailsWith<IllegalStateException> {
            orchestrator.execute(
                plan = HistoricalBackfillPlan(
                    mode = HistoricalBackfillMode.RANGE,
                    resetFlags = false,
                    daily = HistoricalBackfillStage(
                        startDate = tradeDates.first(),
                        endDate = tradeDates.last(),
                        tradeDates = tradeDates,
                        lastContiguousDate = LocalDate(2026, 4, 3),
                    ),
                    fq = HistoricalBackfillStage(
                        startDate = tradeDates.first(),
                        endDate = tradeDates.last(),
                        tradeDates = tradeDates,
                        lastContiguousDate = LocalDate(2026, 4, 3),
                    ),
                    strategy = HistoricalBackfillStage(
                        startDate = strategyDates.first(),
                        endDate = strategyDates.last(),
                        tradeDates = strategyDates,
                        lastContiguousDate = LocalDate(2026, 4, 7),
                    ),
                )
            ) { _, _ -> }
        }

        assertEquals(
            listOf(
                "calendar",
                "stockBasic",
                "sync:2026-04-07",
                "dailyFq:2026-04-07",
                "strategy:2026-04-08",
            ),
            steps
        )
    }
}

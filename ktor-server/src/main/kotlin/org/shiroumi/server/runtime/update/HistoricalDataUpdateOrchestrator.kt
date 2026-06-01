package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.common.updater.updateStockBasic
import org.shiroumi.database.stock.refreshStockDailyFq
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge
import utils.logger
import java.time.ZoneId

private val logger by logger("HistoricalDataUpdateOrchestrator")

/**
 * 盘后历史更新编排器。
 *
 * 这个对象把旧项目中的“每日 16:30 后数据更新”显式收敛成一条可被新架构调度的流水线。
 * 当前阶段先复刻旧语义，不改业务步骤，只把职责边界收紧：
 *
 * 1. 更新交易日历
 * 2. 更新股票基础信息
 * 3. 按交易日历批处理同步 Tushare 日线事实并落库
 * 4. 同步 Tushare limit_list_d 涨跌停、炸板事实
 * 5. 更新复权价格
 * 6. 通过 strategy-service 重建日频策略数据
 *
 * 重要约束：
 * - 最终落库的数据仍必须来自旧链路已经使用的 Tushare 接口
 * - `DAY` 日线更新必须保留“按未完成交易日遍历”的高吞吐模型
 * - 策略阶段统一通过 strategy-service `RebuildDate` 指令发起；service 不可达时通过补偿队列重试
 */
class HistoricalDataUpdateOrchestrator(
    private val historicalDailyBatchSyncService: HistoricalDailyBatchSyncService =
        DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher =
                org.shiroumi.server.dataprovider.adapter.TushareHistoricalDailyBatchFetcher(),
            compensationTaskService = defaultCompensationTaskService()
        ),
    private val compensationTaskService: DataCompensationTaskService = defaultCompensationTaskService(),
    private val limitListDSyncService: LimitListDSyncService =
        DefaultLimitListDSyncService(
            remoteLimitListDFetcher = org.shiroumi.server.dataprovider.adapter.TushareLimitListDFetcher(),
            compensationTaskService = defaultCompensationTaskService()
        ),
    private val open5mSyncService: Open5mSyncService = Open5mSyncService(),
    private val updateCalendarStep: suspend () -> Unit = { updateCalendar() },
    private val updateStockBasicStep: suspend () -> Unit = { updateStockBasic() },
    private val pendingStockDailyFqDateLoader: () -> List<LocalDate> = {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.now(zoneId)
        val todayDate = LocalDate(today.year, today.monthValue, today.dayOfMonth)
        TradingCalendarRepository.findPendingStockDailyFqDates(todayDate)
    },
    private val executeStockDailyFqTradeDate: suspend (LocalDate) -> Unit = { tradeDate ->
        refreshStockDailyFq(listOf(tradeDate))
    },
    private val pendingStrategyDateLoader: () -> List<LocalDate> = {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.now(zoneId)
        TradingCalendarRepository.findPendingStrategyDates(
            LocalDate(today.year, today.monthValue, today.dayOfMonth)
        )
    },
    private val dailyStrategyPreparationStep: ((LocalDate, Throwable) -> Unit) -> StrategyRebuildResult = { onTradeDateFailure ->
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.now(zoneId)
        executePendingStrategyDatesServiceFirst(
            today = LocalDate(today.year, today.monthValue, today.dayOfMonth),
            onTradeDateFailure = onTradeDateFailure,
        )
    }
) {

    suspend fun execute(onStepChanged: suspend (step: String, progress: Int) -> Unit) {
        onStepChanged("更新交易日历", 10)
        updateCalendarStep()

        onStepChanged("更新股票基础信息", 20)
        updateStockBasicStep()

        onStepChanged("更新日线数据", 40)
        historicalDailyBatchSyncService.syncPendingTradeDates()
        compensationTaskService.drain(
            taskType = CompensationTaskType.DAILY_FACT_BY_TRADE_DATE,
            ignoreSchedule = true,
        )
        require(!compensationTaskService.hasOutstanding(CompensationTaskType.DAILY_FACT_BY_TRADE_DATE)) {
            "日线事实补偿任务未清空，阻断后续盘后更新"
        }

        onStepChanged("更新涨跌停炸板数据", 50)
        limitListDSyncService.syncPendingTradeDates()
        compensationTaskService.drain(
            taskType = CompensationTaskType.LIMIT_LIST_D_BY_TRADE_DATE,
            ignoreSchedule = true,
        )
        require(!compensationTaskService.hasOutstanding(CompensationTaskType.LIMIT_LIST_D_BY_TRADE_DATE)) {
            "涨跌停炸板补偿任务未清空，阻断后续盘后更新"
        }

        onStepChanged("更新开盘5min数据", 56)
        // 每日首根 5min（研究层日内预警维度）。幂等增量：只补 open5m 缺口交易日；失败不阻断主链路
        // （研究支撑数据，非关键价格路径），缺口由次日盘后重跑自愈。
        runCatching { open5mSyncService.syncPendingDates() }
            .onFailure { logger.warning("[open5m] 盘后追平异常（不阻断主链路）: ${it.message}") }

        onStepChanged("更新复权价格", 62)
        runPendingFqStage()
        compensationTaskService.drain(
            taskType = CompensationTaskType.DAILY_FQ_BY_TRADE_DATE,
            ignoreSchedule = true,
        )
        require(!compensationTaskService.hasOutstanding(CompensationTaskType.DAILY_FQ_BY_TRADE_DATE)) {
            "复权补偿任务未清空，阻断后续盘后更新"
        }

        onStepChanged("预处理策略日频数据", 76)
        runPendingStrategyStage()
        compensationTaskService.drain(
            taskType = CompensationTaskType.STRATEGY_BY_TRADE_DATE,
            ignoreSchedule = true,
        )
        require(!compensationTaskService.hasOutstanding(CompensationTaskType.STRATEGY_BY_TRADE_DATE)) {
            "策略补偿任务未清空，阻断后续盘后更新"
        }
    }

    private suspend fun runPendingFqStage() {
        pendingStockDailyFqDateLoader().forEach { tradeDate ->
            runCatching {
                executeStockDailyFqTradeDate(tradeDate)
            }.onFailure { error ->
                compensationTaskService.enqueueFailure(
                    taskType = CompensationTaskType.DAILY_FQ_BY_TRADE_DATE,
                    tradeDate = tradeDate,
                    sourceStage = "HISTORICAL_DATA_UPDATE_ORCHESTRATOR",
                    lastError = error.message ?: "unknown",
                )
            }
        }
    }

    private suspend fun runPendingStrategyStage() {
        while (pendingStrategyDateLoader().isNotEmpty()) {
            val result = dailyStrategyPreparationStep { tradeDate, error ->
                compensationTaskService.enqueueFailure(
                    taskType = CompensationTaskType.STRATEGY_BY_TRADE_DATE,
                    tradeDate = tradeDate,
                    sourceStage = "HISTORICAL_DATA_UPDATE_ORCHESTRATOR",
                    lastError = error.message ?: "unknown",
                )
            }
            compensationTaskService.drain(
                taskType = CompensationTaskType.STRATEGY_BY_TRADE_DATE,
                ignoreSchedule = true,
            )
            require(!compensationTaskService.hasOutstanding(CompensationTaskType.STRATEGY_BY_TRADE_DATE)) {
                "策略补偿任务未清空，阻断后续盘后更新"
            }
            if (result.failedDate == null && pendingStrategyDateLoader().isEmpty()) {
                return
            }
            if (result.failedDate == null) {
                continue
            }
        }
    }
}

/**
 * 一组 trade date 通过 strategy-service 重建的执行结果。
 *
 * 表示"调用 service 完成或失败"，不再隐含本地 fallback 语义。
 */
data class StrategyRebuildResult(
    val processedDates: List<LocalDate>,
    val failedDate: LocalDate? = null,
    val failure: Throwable? = null,
)

internal fun defaultCompensationTaskService(): DataCompensationTaskService =
    DataCompensationTaskService(
        dailyFactHandler = { tradeDate ->
            val result = DefaultHistoricalDailyBatchSyncService(
                remoteHistoricalDailyBatchFetcher =
                    org.shiroumi.server.dataprovider.adapter.TushareHistoricalDailyBatchFetcher(),
                compensationTaskService = null,
            ).syncTradeDates(listOf(tradeDate))
            requireTradeDateCompleted(
                taskType = CompensationTaskType.DAILY_FACT_BY_TRADE_DATE,
                tradeDate = tradeDate,
                completed = tradeDate in result.completedDates && result.enqueuedDates.isEmpty(),
                detail = "completedDates=${result.completedDates}, enqueuedDates=${result.enqueuedDates}",
            )
        },
        limitListDHandler = { tradeDate ->
            val result = DefaultLimitListDSyncService(
                remoteLimitListDFetcher = org.shiroumi.server.dataprovider.adapter.TushareLimitListDFetcher(),
                compensationTaskService = null,
            ).syncTradeDates(listOf(tradeDate))
            requireTradeDateCompleted(
                taskType = CompensationTaskType.LIMIT_LIST_D_BY_TRADE_DATE,
                tradeDate = tradeDate,
                completed = result.completedDates == listOf(tradeDate) && result.enqueuedDates.isEmpty() &&
                    TradingCalendarRepository.isLimitListDUpdated(tradeDate),
                detail = "completedDates=${result.completedDates}, enqueuedDates=${result.enqueuedDates}",
            )
        },
        dailyFqHandler = { tradeDate ->
            refreshStockDailyFq(listOf(tradeDate))
            requireTradeDateCompleted(
                taskType = CompensationTaskType.DAILY_FQ_BY_TRADE_DATE,
                tradeDate = tradeDate,
                completed = tradeDate !in TradingCalendarRepository.findPendingStockDailyFqDates(tradeDate),
                detail = "tradeDate still pending in stockDailyFq stage",
            )
        },
        strategyHandler = { tradeDate ->
            executeStrategyDateServiceFirst(tradeDate)
                .also { result ->
                    requireTradeDateCompleted(
                        taskType = CompensationTaskType.STRATEGY_BY_TRADE_DATE,
                        tradeDate = tradeDate,
                        completed = result.failedDate == null && tradeDate in result.processedDates &&
                            tradeDate !in TradingCalendarRepository.findPendingStrategyDates(tradeDate),
                        detail = "processedDates=${result.processedDates}, failedDate=${result.failedDate}",
                    )
                }
        }
    )

private fun executePendingStrategyDatesServiceFirst(
    today: LocalDate,
    onTradeDateFailure: ((LocalDate, Throwable) -> Unit)? = null,
): StrategyRebuildResult {
    val pendingDates = TradingCalendarRepository.findPendingStrategyDates(today).takeLast(120)
    if (pendingDates.isEmpty()) {
        return StrategyRebuildResult(processedDates = emptyList())
    }

    val processedDates = mutableListOf<LocalDate>()
    pendingDates.forEach { tradeDate ->
        val result = executeStrategyDateServiceFirst(tradeDate)
        if (result.failedDate != null) {
            result.failure?.let { onTradeDateFailure?.invoke(tradeDate, it) }
            return StrategyRebuildResult(
                processedDates = processedDates,
                failedDate = tradeDate,
                failure = result.failure,
            )
        }
        processedDates += tradeDate
    }
    return StrategyRebuildResult(processedDates = processedDates)
}

/**
 * 通过 strategy-service `RebuildDate` 指令完成单个交易日的策略重建。
 *
 * service ack 接受 → 视为已交付，进入审计落库由 service 完成；
 * service 拒绝或不可达 → 返回失败，由补偿队列指数退避重试，最终仍只通过 service 重做，
 * Ktor 不再持有本地 fallback 路径。
 */
private fun executeStrategyDateServiceFirst(tradeDate: LocalDate): StrategyRebuildResult {
    val ack = kotlinx.coroutines.runBlocking {
        StrategyRuntimeBridge.rebuildPostMarketDate(
            tradeDate = tradeDate,
            reason = "historical-data-update-orchestrator"
        )
    }
    return if (ack.accepted) {
        StrategyRebuildResult(processedDates = listOf(tradeDate))
    } else {
        StrategyRebuildResult(
            processedDates = emptyList(),
            failedDate = tradeDate,
            failure = IllegalStateException(
                "strategy-service rebuild rejected: ${ack.message}"
            )
        )
    }
}

private fun requireTradeDateCompleted(
    taskType: CompensationTaskType,
    tradeDate: LocalDate,
    completed: Boolean,
    detail: String,
) {
    require(completed) {
        "补偿任务未完成 | taskType=${taskType.name}, tradeDate=$tradeDate, detail=$detail"
    }
}

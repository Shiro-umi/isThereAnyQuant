package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import utils.logger

private val logger by logger("PostMarketOrchestrator")

/**
 * 盘后业务编排 owner。
 *
 * - 整链路 suspend，无 runBlocking 桥接
 * - 通过 [PostMarketRebuildPolicy] 注入历史窗口/并发/精度
 * - 直接调度 strategy-service 内部的 [PostMarketPreparationJob]
 *
 * database 仅提供 Repository / TradingCalendar 等持久化能力。
 */
object PostMarketOrchestrator {

    data class ExecutionResult(
        val processedDates: List<LocalDate>,
        val failedDate: LocalDate? = null,
        val failure: Throwable? = null,
    )

    suspend fun executePendingDailyTasks(
        today: LocalDate,
        policy: PostMarketRebuildPolicy = PostMarketRebuildPolicy.default(),
    ): ExecutionResult =
        executePendingDailyTasksCatching(today = today, policy = policy).also { throwIfFailed(it) }

    suspend fun executePendingDailyTasksCatching(
        today: LocalDate,
        policy: PostMarketRebuildPolicy = PostMarketRebuildPolicy.default(),
        onTradeDateFailure: ((LocalDate, Throwable) -> Unit)? = null,
    ): ExecutionResult {
        val pendingDates = TradingCalendarRepository.findPendingStrategyDates(today)
        val pendingRecent = pendingDates.takeLast(policy.pendingDailyTaskWindow)

        if (pendingRecent.isEmpty()) {
            logger.info("没有待处理的策略数据预处理任务(最近${policy.pendingDailyTaskWindow}个交易日内)。")
            return ExecutionResult(processedDates = emptyList())
        }

        logger.info(
            "开始推演策略状态(最近${policy.pendingDailyTaskWindow}个交易日)，待处理天数: ${pendingRecent.size}, " +
                "日期列表: $pendingRecent"
        )
        return executeTradeDatesCatching(
            tradeDates = pendingRecent,
            policy = policy,
            onTradeDateFailure = onTradeDateFailure,
        )
    }

    suspend fun executeTradeDates(
        tradeDates: List<LocalDate>,
        policy: PostMarketRebuildPolicy = PostMarketRebuildPolicy.default(),
    ): ExecutionResult =
        executeTradeDatesCatching(tradeDates = tradeDates, policy = policy).also { throwIfFailed(it) }

    suspend fun executeTradeDatesCatching(
        tradeDates: List<LocalDate>,
        policy: PostMarketRebuildPolicy = PostMarketRebuildPolicy.default(),
        onTradeDateFailure: ((LocalDate, Throwable) -> Unit)? = null,
    ): ExecutionResult {
        if (tradeDates.isEmpty()) {
            logger.info("没有待处理的策略数据预处理任务。")
            return ExecutionResult(processedDates = emptyList())
        }

        val firstPreviousTradeDate = TradingCalendarRepository.findPreviousTradingDate(tradeDates.first())
        var previousCurrentPositionSymbols = if (firstPreviousTradeDate != null) {
            DailyProfitPredictionSelectionRepository.findSelectedSymbolsByTargetDate(firstPreviousTradeDate)
        } else {
            emptySet()
        }
        var currentPositionSymbols = DailyProfitPredictionSelectionRepository.findSelectedSymbolsByTargetDate(
            tradeDates.first(),
        )
        val processedDates = mutableListOf<LocalDate>()

        tradeDates.forEach { tradeDate ->
            val startDate = computeStartDate(tradeDate, policy.historyLookbackDays)

            logger.info("正在推演 $tradeDate 的策略状态...")
            try {
                val result = PostMarketPreparationJob.run(
                    tradeDate = tradeDate,
                    startDate = startDate,
                    endDate = tradeDate,
                    previousCurrentPositionSymbols = previousCurrentPositionSymbols,
                    currentPositionSymbols = currentPositionSymbols,
                    requiredHistory = policy.requiredHistory,
                    signalBasis = policy.signalBasis,
                    chunkSize = policy.chunkSize,
                    parallelism = policy.parallelism,
                )
                previousCurrentPositionSymbols = currentPositionSymbols
                currentPositionSymbols = result.nextPositionSymbols

                TradingCalendarRepository.markStrategyUpdated(listOf(tradeDate))
                processedDates += tradeDate
            } catch (e: Exception) {
                logger.error("推演 $tradeDate 策略状态失败: ${e.message}\n${e.stackTraceToString()}")
                onTradeDateFailure?.invoke(tradeDate, e)
                return ExecutionResult(
                    processedDates = processedDates,
                    failedDate = tradeDate,
                    failure = e,
                )
            }
        }
        logger.info("策略状态推演追平完成，共处理 ${tradeDates.size} 个交易日。")
        return ExecutionResult(processedDates = processedDates)
    }

    private fun computeStartDate(tradeDate: LocalDate, historyLookbackDays: Long): LocalDate {
        val startJavaDate = java.time.LocalDate.parse(tradeDate.toString()).minusDays(historyLookbackDays)
        return LocalDate(startJavaDate.year, startJavaDate.monthValue, startJavaDate.dayOfMonth)
    }

    private fun throwIfFailed(result: ExecutionResult) {
        if (result.failedDate == null) return
        throw IllegalStateException(
            "推演 ${result.failedDate} 策略状态失败，已完成 ${result.processedDates.size} 个交易日",
            result.failure,
        )
    }
}

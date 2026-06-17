package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import org.shiroumi.database.strategy.daily.repository.DailyStrategyHoldingRepository
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
        candleProvider: ((LocalDate) -> Map<String, Candle>)? = null,
        // 重建专用：区间有序交易日索引，供 tradingDaysSince 段内二分定位（不改数值）。在线追平传 null。
        tradeDateIndex: List<LocalDate>? = null,
    ): ExecutionResult {
        if (tradeDates.isEmpty()) {
            logger.info("没有待处理的策略数据预处理任务。")
            return ExecutionResult(processedDates = emptyList())
        }

        // 持仓链初值：从前一交易日的持仓状态表加载，逐日由状态机推进。
        val firstPreviousTradeDate = TradingCalendarRepository.findPreviousTradingDate(tradeDates.first())
        var previousHoldings: List<DailyHoldingState> = if (firstPreviousTradeDate != null) {
            DailyStrategyHoldingRepository.findByTradeDate(firstPreviousTradeDate)
        } else {
            emptyList()
        }
        val processedDates = mutableListOf<LocalDate>()

        // 持仓链严格串行：previousHoldings 依赖前一交易日产出，不得并行/乱序。
        tradeDates.forEach { tradeDate ->
            val startDate = computeStartDate(tradeDate, policy.historyLookbackDays)

            logger.info("正在推演 $tradeDate 的策略状态...")
            try {
                val result = PostMarketPreparationJob.run(
                    tradeDate = tradeDate,
                    startDate = startDate,
                    endDate = tradeDate,
                    previousHoldings = previousHoldings,
                    requiredHistory = policy.requiredHistory,
                    signalBasis = policy.signalBasis,
                    chunkSize = policy.chunkSize,
                    parallelism = policy.parallelism,
                    candleProvider = candleProvider,
                    tradeDateIndex = tradeDateIndex,
                )
                previousHoldings = result.holdings

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

    /**
     * 全历史区间重建入口——RebuildStrategyRange 专用，与在线盘后追平 [executeTradeDatesCatching] 区分：
     *
     * 1. 强制清表：逐日推进前 deleteByDateRange([start..end]) 清空区间内残留旧持仓链，消除链式污染。
     * 2. 严格串行：复用 [executeTradeDatesCatching] 的逐日 forEach（previousHoldings 链依赖，不并行/乱序）。
     * 3. 滑窗供给器：注入 [SlidingWindowCandleProvider]，按 ~250 交易日分段预取日线，段内逐日复用，
     *    消除「同日被持仓推进取两次（当日 + 次日作信号日）」的 2 倍冗余 DB 往返。
     *
     * 区间外（start 前一交易日的链初值、各因子/情绪滚动状态预热窗）不清、不动。
     */
    suspend fun rebuildTradeDatesCatching(
        tradeDates: List<LocalDate>,
        policy: PostMarketRebuildPolicy = PostMarketRebuildPolicy.default(),
        onTradeDateFailure: ((LocalDate, Throwable) -> Unit)? = null,
    ): ExecutionResult {
        if (tradeDates.isEmpty()) {
            logger.info("[策略区间重建] 无待重建交易日。")
            return ExecutionResult(processedDates = emptyList())
        }
        val start = tradeDates.first()
        val end = tradeDates.last()

        // 强制清表：区间内整段持仓行先清，再逐日重算覆盖，避免旧链残留。
        val cleared = DailyStrategyHoldingRepository.deleteByDateRange(start, end)
        logger.info("[策略区间重建] 已清空持仓区间 [$start..$end] 残留行=$cleared，开始逐日严格串行重算")

        val candleProvider = SlidingWindowCandleProvider(tradeDates)
        return executeTradeDatesCatching(
            tradeDates = tradeDates,
            policy = policy,
            onTradeDateFailure = onTradeDateFailure,
            candleProvider = candleProvider,
            tradeDateIndex = tradeDates,
        )
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

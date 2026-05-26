package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.common.updater.updateStockBasic
import org.shiroumi.database.stock.refreshStockDailyFq
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge
import utils.logger

private val logger by logger("HistoricalBackfillOrchestrator")

class HistoricalBackfillOrchestrator(
    private val historicalDailyBatchSyncService: HistoricalDailyBatchSyncService =
        DefaultHistoricalDailyBatchSyncService(
            remoteHistoricalDailyBatchFetcher =
                org.shiroumi.server.dataprovider.adapter.TushareHistoricalDailyBatchFetcher()
        ),
    private val updateCalendarStep: suspend () -> Unit = { updateCalendar() },
    private val updateStockBasicStep: suspend () -> Unit = { updateStockBasic() },
    private val resetStockDailyFlagsStep: (HistoricalBackfillStage) -> Unit = { stage ->
        if (!stage.isEmpty) {
            TradingCalendarRepository.resetStockDailyUpdated(stage.startDate!!, stage.endDate!!)
        }
    },
    private val resetStockDailyFqFlagsStep: (HistoricalBackfillStage) -> Unit = { stage ->
        if (!stage.isEmpty) {
            TradingCalendarRepository.resetStockDailyFqUpdated(stage.startDate!!, stage.endDate!!)
        }
    },
    private val resetStrategyFlagsStep: (HistoricalBackfillStage) -> Unit = { stage ->
        if (!stage.isEmpty) {
            TradingCalendarRepository.resetStrategyUpdated(stage.startDate!!, stage.endDate!!)
        }
    },
    private val updateStockDailyFqStep: suspend (List<LocalDate>) -> List<LocalDate> = { refreshStockDailyFq(it) },
    private val executeStrategyStep: (List<LocalDate>) -> Unit = { tradeDates ->
        backfillStrategyDatesViaService(tradeDates)
    },
) {
    suspend fun execute(plan: HistoricalBackfillPlan, onStepChanged: suspend (step: String, progress: Int) -> Unit) {
        if (plan.daily.isEmpty && plan.fq.isEmpty && plan.strategy.isEmpty) {
            logger.info("没有需要回补的阶段。")
            return
        }

        logger.info(
            "开始执行历史回补 | mode=${plan.mode}, resetFlags=${plan.resetFlags}, " +
                "daily=[start=${plan.daily.startDate}, end=${plan.daily.endDate}, count=${plan.daily.tradeDates.size}, lastContiguous=${plan.daily.lastContiguousDate}], " +
                "fq=[start=${plan.fq.startDate}, end=${plan.fq.endDate}, count=${plan.fq.tradeDates.size}, lastContiguous=${plan.fq.lastContiguousDate}], " +
                "strategy=[start=${plan.strategy.startDate}, end=${plan.strategy.endDate}, count=${plan.strategy.tradeDates.size}, lastContiguous=${plan.strategy.lastContiguousDate}]"
        )

        onStepChanged("更新交易日历", 5)
        updateCalendarStep()

        onStepChanged("更新股票基础信息", 10)
        updateStockBasicStep()

        if (plan.resetFlags) {
            onStepChanged("重置回补区间标记", 20)
            resetStockDailyFlagsStep(plan.daily)
            resetStockDailyFqFlagsStep(plan.fq)
            resetStrategyFlagsStep(plan.strategy)
        }

        if (!plan.daily.isEmpty) {
            onStepChanged("追平历史日线数据", 40)
            val result = historicalDailyBatchSyncService.syncTradeDates(plan.daily.tradeDates)
            require(result.enqueuedDates.isEmpty()) {
                "历史日线回补存在失败交易日: ${result.enqueuedDates.joinToString(",")}"
            }
        } else {
            logger.info("历史日线阶段无需回补。")
        }

        if (!plan.fq.isEmpty) {
            onStepChanged("更新复权价格", 65)
            updateStockDailyFqStep(plan.fq.tradeDates)
        } else {
            logger.info("复权阶段无需回补。")
        }

        if (!plan.strategy.isEmpty) {
            onStepChanged("追平滚动情绪数据", 85)
            executeStrategyStep(plan.strategy.tradeDates)
        } else {
            logger.info("策略阶段无需回补。")
        }
    }
}

/**
 * 通过 strategy-service `RebuildDate` 指令逐日完成历史回补阶段的策略重建。
 *
 * 与 `HistoricalDataUpdateOrchestrator.executeStrategyDateServiceFirst` 语义一致：
 * service ack 接受视为已交付；service 拒绝或不可达直接抛出 `IllegalStateException`，
 * 由调用方决定是否进入补偿队列。Ktor 不再持有任何本地 facade fallback 路径。
 */
private fun backfillStrategyDatesViaService(tradeDates: List<LocalDate>) {
    tradeDates.forEach { tradeDate ->
        val ack = kotlinx.coroutines.runBlocking {
            StrategyRuntimeBridge.rebuildPostMarketDate(
                tradeDate = tradeDate,
                reason = "historical-backfill-orchestrator",
            )
        }
        require(ack.accepted) {
            "strategy-service backfill rejected for $tradeDate: ${ack.message}"
        }
    }
}

package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.strategy.daily.StrategyStateSchemaBootstrap
import org.shiroumi.server.runtime.update.HistoricalBackfillOptions
import org.shiroumi.server.runtime.update.HistoricalBackfillOrchestrator
import org.shiroumi.server.runtime.update.HistoricalBackfillRangeResolver
import org.shiroumi.server.runtime.update.HistoricalBackfillStatusReconciler
import utils.logger

private val logger by logger("BackfillHistoricalData")

fun main() {
    try {
        Class.forName("com.mysql.cj.jdbc.Driver")
    } catch (_: Exception) {}
    try {
        Class.forName("org.h2.Driver")
    } catch (_: Exception) {}

    ConfigManager.load()
    StrategyStateSchemaBootstrap.ensureSchema()

    runBlocking { runHistoricalBackfill() }
}

suspend fun runHistoricalBackfill(
    options: HistoricalBackfillOptions = HistoricalBackfillOptions.fromSystemProperties(),
    updateCalendarStep: suspend () -> Unit = { updateCalendar() },
    resolvePlan: (HistoricalBackfillOptions) -> org.shiroumi.server.runtime.update.HistoricalBackfillPlan? = {
        HistoricalBackfillRangeResolver().resolve(it)
    },
    executePlan: suspend (org.shiroumi.server.runtime.update.HistoricalBackfillPlan) -> Unit = { plan ->
        HistoricalBackfillOrchestrator().execute(plan) { step, progress ->
            logger.info("[$progress%] $step")
        }
    },
    reconcileStatus: () -> Unit = {
        HistoricalBackfillStatusReconciler().reconcile()
    },
) {
    // 空库场景下必须先更新交易日历，否则无法解析回补区间。
    updateCalendarStep()
    logger.info("交易日历更新完成，开始解析历史回补区间...")

    val plan = resolvePlan(options)

    if (plan == null) {
        logger.info("没有需要执行的历史回补区间，任务结束。")
        reconcileStatus()
        return
    }

    logger.info(
        "历史回补计划已生成 | mode=${plan.mode}, resetFlags=${plan.resetFlags}, " +
            "daily=[start=${plan.daily.startDate}, end=${plan.daily.endDate}, count=${plan.daily.tradeDates.size}, lastContiguous=${plan.daily.lastContiguousDate}], " +
            "fq=[start=${plan.fq.startDate}, end=${plan.fq.endDate}, count=${plan.fq.tradeDates.size}, lastContiguous=${plan.fq.lastContiguousDate}], " +
            "strategy=[start=${plan.strategy.startDate}, end=${plan.strategy.endDate}, count=${plan.strategy.tradeDates.size}, lastContiguous=${plan.strategy.lastContiguousDate}]"
    )

    executePlan(plan)
    reconcileStatus()
}

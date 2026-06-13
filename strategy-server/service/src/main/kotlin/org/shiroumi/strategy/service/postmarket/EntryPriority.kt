package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import org.shiroumi.database.stock.StockDailyCandleRepository

/**
 * 每日入场上限开启时的入场优先级口径——信号日 20 日对数收益波动率（前复权收盘）。
 *
 * 盘后状态机推进（[PostMarketPreparationJob]）与持仓跟踪最早跟随日校准重放
 * （`StrategyPositionTrackingRuntime.buildCalibratedSnapshot`）共用此实现，
 * 保证两侧对同一候选排出相同的入场顺序（v5 验证：高波动优先 +1.8pp）。
 */
internal object EntryPriority {

    /** 数据不足 10 根有效收益时返回 0.0（排序中自然落后）。 */
    fun signalDayVolatility20(tsCode: String, signalDate: LocalDate): Double {
        val closes = StockDailyCandleRepository
            .findRecent(tsCode = tsCode, limit = 21, endDateInclusive = signalDate)
            .map { it.getPrice(useAdjusted = true).toDouble() }
            .filter { it > 0.0 }
        if (closes.size < 11) return 0.0
        val logReturns = closes.zipWithNext { prev, cur -> kotlin.math.ln(cur / prev) }
        val mean = logReturns.average()
        return kotlin.math.sqrt(logReturns.sumOf { (it - mean) * (it - mean) } / logReturns.size)
    }
}

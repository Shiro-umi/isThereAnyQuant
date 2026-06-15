package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.database.stock.StockDailyCandleRepository

/**
 * 回测侧入场优先级口径——信号日 20 日对数收益波动率（前复权收盘）。
 *
 * 逐分支复刻生产 `org.shiroumi.strategy.service.postmarket.EntryPriority.signalDayVolatility20`：
 * - 信号日往前取 21 根前复权收盘（findRecent limit=21）
 * - 过滤价格 > 0，有效行 < 11 返回 0.0
 * - 取相邻对数收益的总体标准差（除以 n，非 n-1）
 *
 * 边界约束：backtest 严禁依赖 `:strategy-server:service`，故不 import 生产的 internal EntryPriority，
 * 在此复刻同一口径，保证回测与生产对同一候选排出相同的入场顺序。
 */
fun interface BacktestEntryPriority {
    fun signalDayVolatility20(tsCode: String, signalDate: LocalDate): Double

    companion object {
        /** 基于 stock_db 的实现，复刻生产 EntryPriority 口径。 */
        val Db: BacktestEntryPriority = BacktestEntryPriority { tsCode, signalDate ->
            val closes = StockDailyCandleRepository
                .findRecent(tsCode = tsCode, limit = 21, endDateInclusive = signalDate)
                .map { it.getPrice(useAdjusted = true).toDouble() }
                .filter { it > 0.0 }
            if (closes.size < 11) {
                0.0
            } else {
                val logReturns = closes.zipWithNext { prev, cur -> kotlin.math.ln(cur / prev) }
                val mean = logReturns.average()
                kotlin.math.sqrt(logReturns.sumOf { (it - mean) * (it - mean) } / logReturns.size)
            }
        }
    }
}

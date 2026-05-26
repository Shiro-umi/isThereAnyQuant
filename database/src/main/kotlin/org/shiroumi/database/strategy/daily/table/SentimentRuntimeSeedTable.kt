package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * 盘中情绪运行时种子表。
 *
 * 这张表不是历史结果表，而是“下一交易日盘中快速推演”的运行时输入缓存。
 * 它刻意保持最小化：
 * - 不重复存历史情绪结果
 * - 不存深层 FFT / 回归中间态
 * - 只存盘中恢复最小计算上下文所需的信息
 */
object SentimentRuntimeSeedTable : Table(name = "sentiment_runtime_seed") {
    val scope = varchar("scope", 64)
    val forTradeDate = date("for_trade_date")
    val sourceTradeDate = date("source_trade_date")
    val signalBasis = varchar("signal_basis", 16)
    val requiredHistory = integer("required_history")
    val sampleSize = integer("sample_size")
    val sampleCodesJson = text("sample_codes_json")
    val symbolStatesJson = text("symbol_states_json")
    val bullRatioWindowJson = text("bull_ratio_window_json")
    val marketVolWindowJson = text("market_vol_window_json")
    val accelWindowJson = text("accel_window_json")
    val combinedHistoryJson = text("combined_history_json")
    val totalDays = integer("total_days").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("uk_sentiment_runtime_seed", scope, forTradeDate)
    }
}

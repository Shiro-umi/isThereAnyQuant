package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 单只样本股的情绪计算滚动状态。
 */
@Serializable
data class SymbolSentimentState(
    val tsCode: String,
    val emaShort: Double,
    val emaLong: Double,
    /** 上一根 bar 的 close，用于缺失日 ffill 时计算 return=0。 */
    val prevClose: Double,
    /** 最近 20 日收益率循环队列，按 (nextReturnIndex % 20) 为插入位置。 */
    val recentReturns: List<Double>,
    /** 下一个要写入的索引位置（0..19）。 */
    val nextReturnIndex: Int,
    /** 当前窗口内的有效收益数量（0..20）。 */
    val returnWindowSize: Int,
    /** 当前窗口内收益之和。 */
    val returnSum: Double,
    /** 当前窗口内收益平方之和。 */
    val returnSumSq: Double,
) {
    fun standardDeviation(): Double {
        if (returnWindowSize < 2) return 0.0
        val mean = returnSum / returnWindowSize
        val variance = (returnSumSq / returnWindowSize) - (mean * mean)
        return sqrt(max(variance, 0.0))
    }
}

/**
 * 市场情绪计算的滚动状态。
 */
@Serializable
data class MarketSentimentRollingState(
    val tradeDate: LocalDate,
    val signalBasis: String,
    val sampleCodes: List<String>,
    val symbolStates: List<SymbolSentimentState>,
    /** 最近最多 Z_WINDOW(252) 日的 bullRatio 历史。 */
    val bullRatioHistory: List<Double>,
    /** 最近最多 Z_WINDOW(252) 日的 marketVol 历史。 */
    val marketVolHistory: List<Double>,
    /** 最近最多 Z_WINDOW(252) 日的 accel EMA 历史。 */
    val accelHistory: List<Double>,
    /** 最近最多 Z_WINDOW(252) 日的 combined 历史（用于 overheat guard）。 */
    val combinedHistory: List<Double>,
    /** 累计处理的总天数（可能大于各历史窗口长度，用于 sufficientHistory 判断）。 */
    val totalDays: Int,
)

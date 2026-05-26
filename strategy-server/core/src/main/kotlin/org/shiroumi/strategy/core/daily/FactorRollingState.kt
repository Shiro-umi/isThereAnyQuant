package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 个股因子滚动计算状态。
 *
 * 设计目标：支持 StockFactorCalculator 对单只股票的 O(1) 增量递推，
 * 同时忠实复现原始全量路径的所有计算语义。
 */
@Serializable
data class FactorRollingState(
    val tradeDate: LocalDate,
    val tsCode: String,
    val signalBasis: String,
    val executionBasis: String,
    val requiredHistory: Int,
    val barsCount: Int,
    val emaShort: Double,
    val emaLong: Double,
    val atr: Double,
    val holding: Boolean,
    val stopPrice: Double,
    val holdingDays: Int,
    val shortVolumeSum: Double,
    val longVolumeSum: Double,
    /** 上一根 bar 的 close，用于计算当前 return 与 true range。 */
    val prevClose: Double,
    /** 最近 20 日收益率，按 (index % 20) 循环存储（与全量路径的 recentReturns 数组覆盖顺序一致）。 */
    val recentReturns: List<Double>,
    /** 最近 20 日收盘价，按 (index % 20) 循环存储（用于 momentum20）。 */
    val recentCloses: List<Double>,
    /** 最近 20 日成交量，按 (index % 20) 循环存储（用于 volRatio520 的滚动和更新）。 */
    val recentVolumes: List<Double>,
    /** 20 天前的收盘价，用于当日 momentum20 的快照计算（避免循环数组被覆盖后丢失）。 */
    val momentumBaseClose: Double,
)

package org.shiroumi.quant_kmp.strategy.daily.model

import kotlinx.datetime.LocalDate
import model.PriceBasis

/**
 * 预处理后可直接用于策略计算的标准化日线。
 * raw 用于执行参考；signalBasis 对应的 OHLCV 用于因子/情绪计算。
 */
data class PreparedBar(
    val tsCode: String,
    val date: LocalDate,
    val signalBasis: PriceBasis,
    val executionBasis: PriceBasis,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val executionOpen: Double,
    val executionClose: Double,
    val rawOpen: Double,
    val rawHigh: Double,
    val rawLow: Double,
    val rawClose: Double,
    val rawVolume: Double,
    val qfqOpen: Double,
    val qfqHigh: Double,
    val qfqLow: Double,
    val qfqClose: Double,
    val qfqVolume: Double,
    val hfqFactor: Double,
)
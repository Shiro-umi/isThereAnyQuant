package org.shiroumi.quant_kmp.strategy.daily.model

import model.PriceBasis

/**
 * 单只股票的预处理窗口。
 */
data class PreparedStockWindow(
    val tsCode: String,
    val signalBasis: PriceBasis,
    val executionBasis: PriceBasis,
    val bars: List<PreparedBar>,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val reason: String? = null,
)
package org.shiroumi.quant_kmp.strategy.daily.model

import model.PriceBasis

/**
 * 市场情绪预处理窗口。
 * 约定：情绪计算只需要 close 序列与日收益；这里仍保留完整 bar，方便未来扩展。
 */
data class PreparedMarketWindow(
    val symbols: List<String>,
    val signalBasis: PriceBasis,
    val barsBySymbol: Map<String, List<PreparedBar>>,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val reason: String? = null,
)
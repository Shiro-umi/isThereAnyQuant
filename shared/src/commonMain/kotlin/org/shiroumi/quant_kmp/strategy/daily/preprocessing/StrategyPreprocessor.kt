package org.shiroumi.quant_kmp.strategy.daily.preprocessing

import kotlinx.datetime.LocalDate
import model.PriceBasis
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedMarketWindow
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedStockWindow

interface StrategyPreprocessor {
    fun prepareStockWindows(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        requiredHistory: Int,
        signalBasis: PriceBasis = PriceBasis.HFQ,
        executionBasis: PriceBasis = PriceBasis.RAW,
    ): Map<String, PreparedStockWindow>

    fun prepareMarketWindow(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        requiredHistory: Int,
        signalBasis: PriceBasis = PriceBasis.HFQ,
    ): PreparedMarketWindow
}
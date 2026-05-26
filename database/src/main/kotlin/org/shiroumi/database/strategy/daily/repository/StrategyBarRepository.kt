package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import model.Candle

interface StrategyBarRepository {
    fun getStockHistory(
        tsCode: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<Candle>

    fun getBatchStockHistory(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Map<String, List<Candle>>

    fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float>
}
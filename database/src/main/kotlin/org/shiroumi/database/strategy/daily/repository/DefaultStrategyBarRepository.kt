package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.stock.StockReader

object DefaultStrategyBarRepository : StrategyBarRepository {
    override fun getStockHistory(
        tsCode: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<Candle> = StockReader.getStockHistory(tsCode, startDate, endDate)

    override fun getBatchStockHistory(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Map<String, List<Candle>> = StockReader.getBatchStockHistory(tsCodes, startDate, endDate)

    override fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float> = StockReader.getFirstAdjMap(tsCodes)
}
package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.DailyStrategyHoldingTable
import org.shiroumi.database.transaction

/**
 * 持仓状态——某 `tradeDate` 收盘后仍在持有的一只票及其入场元数据。
 */
data class DailyHoldingState(
    val tradeDate: LocalDate,
    val tsCode: String,
    val entryDate: LocalDate,
    val entryPrice: Double,
    val signalDateLow: Double,
)

object DailyStrategyHoldingRepository {

    fun findByTradeDate(tradeDate: LocalDate): List<DailyHoldingState> =
        stockDb.transaction(DailyStrategyHoldingTable, log = false) {
            DailyStrategyHoldingTable.selectAll()
                .where { DailyStrategyHoldingTable.tradeDate eq tradeDate }
                .orderBy(DailyStrategyHoldingTable.entryDate, SortOrder.ASC)
                .orderBy(DailyStrategyHoldingTable.tsCode, SortOrder.ASC)
                .map(::holdingFromRow)
        }

    fun replaceForDate(tradeDate: LocalDate, holdings: List<DailyHoldingState>) {
        stockDb.transaction(DailyStrategyHoldingTable) {
            DailyStrategyHoldingTable.deleteWhere {
                DailyStrategyHoldingTable.tradeDate eq tradeDate
            }
            if (holdings.isNotEmpty()) {
                DailyStrategyHoldingTable.batchUpsert(holdings) { holding ->
                    this[DailyStrategyHoldingTable.tradeDate] = tradeDate
                    this[DailyStrategyHoldingTable.tsCode] = holding.tsCode
                    this[DailyStrategyHoldingTable.entryDate] = holding.entryDate
                    this[DailyStrategyHoldingTable.entryPrice] = holding.entryPrice
                    this[DailyStrategyHoldingTable.signalDateLow] = holding.signalDateLow
                }
            }
        }
    }

    fun deleteAll(): Int =
        stockDb.transaction(DailyStrategyHoldingTable) {
            DailyStrategyHoldingTable.deleteAll()
        }

    private fun holdingFromRow(row: ResultRow): DailyHoldingState =
        DailyHoldingState(
            tradeDate = row[DailyStrategyHoldingTable.tradeDate],
            tsCode = row[DailyStrategyHoldingTable.tsCode],
            entryDate = row[DailyStrategyHoldingTable.entryDate],
            entryPrice = row[DailyStrategyHoldingTable.entryPrice],
            signalDateLow = row[DailyStrategyHoldingTable.signalDateLow],
        )
}

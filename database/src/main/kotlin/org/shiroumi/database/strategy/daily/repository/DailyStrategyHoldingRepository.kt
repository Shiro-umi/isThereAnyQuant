package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
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

    /**
     * 删除 [start..end]（闭区间）内全部持仓行。
     *
     * 全历史重建入口在逐日推进前调用，强制清空目标区间残留旧链——持仓链依赖前一交易日 previousHoldings
     * 逐日推进，区间内残留的旧 entry 会污染链式初值。区间外（含 start 前一交易日的链初值）不动。
     */
    fun deleteByDateRange(start: LocalDate, end: LocalDate): Int =
        stockDb.transaction(DailyStrategyHoldingTable) {
            DailyStrategyHoldingTable.deleteWhere {
                (DailyStrategyHoldingTable.tradeDate greaterEq start) and
                    (DailyStrategyHoldingTable.tradeDate lessEq end)
            }
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

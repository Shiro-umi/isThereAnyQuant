package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.strategy.core.daily.TargetPosition
import org.shiroumi.database.strategy.daily.table.DailyTargetPortfolioTable
import org.shiroumi.database.transaction

data class DailyTargetSelection(
    val tradeDate: LocalDate,
    val tsCode: String,
    val selectionScore: Double,
)

data class BacktestTargetPortfolioRecord(
    val tradeDate: LocalDate,
    val targetDate: LocalDate,
    val tsCode: String,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val selectionReason: String?,
)

object DailyTargetPortfolioRepository {
    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyTargetPortfolioTable, log = false) {
            DailyTargetPortfolioTable.selectAll()
                .where { DailyTargetPortfolioTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyTargetPortfolioTable, log = false) {
            DailyTargetPortfolioTable.deleteWhere { DailyTargetPortfolioTable.tradeDate eq tradeDate }
        }
    }

    fun replaceForDate(
        tradeDate: LocalDate,
        positions: List<TargetPosition>,
    ) {
        stockDb.transaction(DailyTargetPortfolioTable) {
            DailyTargetPortfolioTable.batchUpsert(positions) { position ->
                this[DailyTargetPortfolioTable.tradeDate] = tradeDate
                this[DailyTargetPortfolioTable.targetDate] = position.targetDate
                this[DailyTargetPortfolioTable.tsCode] = position.tsCode
                this[DailyTargetPortfolioTable.selectionScore] = position.selectionScore
                this[DailyTargetPortfolioTable.selected] = position.selected
                this[DailyTargetPortfolioTable.targetWeight] = position.targetWeight
                this[DailyTargetPortfolioTable.sentimentExposure] = position.sentimentExposure
                this[DailyTargetPortfolioTable.selectionReason] = position.selectionReason
            }
        }
    }

    fun findSelectionsByTradeDates(tradeDates: List<LocalDate>): Map<LocalDate, List<DailyTargetSelection>> {
        if (tradeDates.isEmpty()) return emptyMap()

        return stockDb.transaction(DailyTargetPortfolioTable, log = false) {
            DailyTargetPortfolioTable.selectAll()
                .where {
                    (DailyTargetPortfolioTable.tradeDate inList tradeDates) and
                        (DailyTargetPortfolioTable.selected eq true)
                }
                .orderBy(DailyTargetPortfolioTable.tradeDate, SortOrder.ASC)
                .orderBy(DailyTargetPortfolioTable.selectionScore, SortOrder.DESC)
                .orderBy(DailyTargetPortfolioTable.tsCode, SortOrder.ASC)
                .map {
                    DailyTargetSelection(
                        tradeDate = it[DailyTargetPortfolioTable.tradeDate],
                        tsCode = it[DailyTargetPortfolioTable.tsCode],
                        selectionScore = it[DailyTargetPortfolioTable.selectionScore],
                    )
                }
                .groupBy { it.tradeDate }
        }
    }

    fun findSelectedSymbolsByTargetDate(targetDate: LocalDate): Set<String> {
        return findSelectionsByTargetDate(targetDate)
            .map { it.tsCode }
            .toSet()
    }

    fun findSelectionsByTargetDate(targetDate: LocalDate): List<DailyTargetSelection> {
        return findSelectionsByTargetDates(listOf(targetDate))[targetDate].orEmpty()
    }

    fun findBacktestTargetsByTargetDate(targetDate: LocalDate): List<BacktestTargetPortfolioRecord> {
        return stockDb.transaction(DailyTargetPortfolioTable, log = false) {
            DailyTargetPortfolioTable.selectAll()
                .where { DailyTargetPortfolioTable.targetDate eq targetDate }
                .orderBy(DailyTargetPortfolioTable.selectionScore, SortOrder.DESC)
                .orderBy(DailyTargetPortfolioTable.tsCode, SortOrder.ASC)
                .map {
                    BacktestTargetPortfolioRecord(
                        tradeDate = it[DailyTargetPortfolioTable.tradeDate],
                        targetDate = it[DailyTargetPortfolioTable.targetDate],
                        tsCode = it[DailyTargetPortfolioTable.tsCode],
                        selected = it[DailyTargetPortfolioTable.selected],
                        targetWeight = it[DailyTargetPortfolioTable.targetWeight],
                        sentimentExposure = it[DailyTargetPortfolioTable.sentimentExposure],
                        selectionReason = it[DailyTargetPortfolioTable.selectionReason],
                    )
                }
        }
    }

    fun findSelectionsByTargetDates(targetDates: List<LocalDate>): Map<LocalDate, List<DailyTargetSelection>> {
        if (targetDates.isEmpty()) return emptyMap()

        return stockDb.transaction(DailyTargetPortfolioTable, log = false) {
            DailyTargetPortfolioTable.selectAll()
                .where {
                    (DailyTargetPortfolioTable.targetDate inList targetDates.distinct()) and
                        (DailyTargetPortfolioTable.selected eq true)
                }
                .orderBy(DailyTargetPortfolioTable.targetDate, SortOrder.ASC)
                .orderBy(DailyTargetPortfolioTable.selectionScore, SortOrder.DESC)
                .orderBy(DailyTargetPortfolioTable.tsCode, SortOrder.ASC)
                .map {
                    val targetDate = it[DailyTargetPortfolioTable.targetDate]
                    targetDate to DailyTargetSelection(
                        tradeDate = it[DailyTargetPortfolioTable.tradeDate],
                        tsCode = it[DailyTargetPortfolioTable.tsCode],
                        selectionScore = it[DailyTargetPortfolioTable.selectionScore],
                    )
                }
                .groupBy({ it.first }, { it.second })
        }
    }
}

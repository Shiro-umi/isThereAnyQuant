package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.sentiment.table.SentimentFactorDailyTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object SentimentFactorDailyRepository {
    fun upsert(records: List<SentimentFactorDailyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.batchUpsert(records) { record ->
                this[SentimentFactorDailyTable.tradeDate] = record.tradeDate
                SentimentFactorDailyTable.factorColumns.forEach { (name, column) ->
                    this[column] = record.factors[name]
                }
                this[SentimentFactorDailyTable.y1Raw] = record.y1Raw
                this[SentimentFactorDailyTable.y2Raw] = record.y2Raw
                this[SentimentFactorDailyTable.y3Raw] = record.y3Raw
                this[SentimentFactorDailyTable.yComposite] = record.yComposite
                this[SentimentFactorDailyTable.notes] = record.notes
            }
        }
    }

    fun findByDate(tradeDate: LocalDate): SentimentFactorDailyRecord? =
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.selectAll()
                .where { SentimentFactorDailyTable.tradeDate eq tradeDate }
                .limit(1)
                .map(::toRecord)
                .firstOrNull()
        }

    fun findBetween(startDate: LocalDate, endDate: LocalDate): List<SentimentFactorDailyRecord> =
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.selectAll()
                .where { SentimentFactorDailyTable.tradeDate.between(startDate, endDate) }
                .orderBy(SentimentFactorDailyTable.tradeDate, SortOrder.ASC)
                .map(::toRecord)
        }

    fun countRows(startDate: LocalDate, endDate: LocalDate): Long =
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.selectAll()
                .where { SentimentFactorDailyTable.tradeDate.between(startDate, endDate) }
                .count()
        }

    private fun toRecord(row: ResultRow): SentimentFactorDailyRecord =
        SentimentFactorDailyRecord(
            tradeDate = row[SentimentFactorDailyTable.tradeDate],
            factors = SentimentFactorDailyTable.factorColumns.mapValues { (_, column) -> row[column] },
            y1Raw = row[SentimentFactorDailyTable.y1Raw],
            y2Raw = row[SentimentFactorDailyTable.y2Raw],
            y3Raw = row[SentimentFactorDailyTable.y3Raw],
            yComposite = row[SentimentFactorDailyTable.yComposite],
            notes = row[SentimentFactorDailyTable.notes],
        )
}

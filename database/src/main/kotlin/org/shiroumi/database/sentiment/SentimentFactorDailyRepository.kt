package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.sentiment.table.SentimentFactorDailyTable
import org.shiroumi.database.stock.table.LimitListDTable
import org.shiroumi.database.stock.table.StockDailyDataTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object SentimentFactorDailyRepository {
    fun rebuildAGroup(startDate: LocalDate, endDate: LocalDate): Int {
        val stockFacts = findStockFactsForAGroup(startDate, endDate)
        val limits = findLimitSummaries(startDate, endDate)
        val records = SentimentFactorDailyCalculator.calculate(
            facts = stockFacts,
            limitSummaries = limits,
            startDate = startDate,
            endDate = endDate,
        )
        upsertAGroup(records)
        return records.size
    }

    fun rebuildBAndEGroup(startDate: LocalDate, endDate: LocalDate): Int {
        val stockFacts = findStockFactsForAGroup(startDate, endDate)
        val limits = findLimitSummaries(startDate, endDate)
        val records = SentimentFactorDailyCalculator.calculate(
            facts = stockFacts,
            limitSummaries = limits,
            startDate = startDate,
            endDate = endDate,
        )
        upsertBAndEGroup(records)
        return records.size
    }

    fun rebuildCGroup(startDate: LocalDate, endDate: LocalDate): Int {
        val stockFacts = findStockFactsForAGroup(startDate, endDate)
        val limits = findLimitSummaries(startDate, endDate)
        val records = SentimentFactorDailyCalculator.calculate(
            facts = stockFacts,
            limitSummaries = limits,
            startDate = startDate,
            endDate = endDate,
        )
        upsertCGroup(records)
        return records.size
    }

    fun upsertAGroup(records: List<SentimentFactorDailyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.batchUpsert(records) { record ->
                this[SentimentFactorDailyTable.tradeDate] = record.tradeDate
                A_GROUP_FACTOR_NAMES.forEach { name ->
                    this[SentimentFactorDailyTable.factorColumns.getValue(name)] = record.factors[name]
                }
                this[SentimentFactorDailyTable.y1Raw] = record.y1Raw
            }
        }
    }

    fun upsertBAndEGroup(records: List<SentimentFactorDailyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.batchUpsert(records) { record ->
                this[SentimentFactorDailyTable.tradeDate] = record.tradeDate
                B_E_GROUP_FACTOR_NAMES.forEach { name ->
                    this[SentimentFactorDailyTable.factorColumns.getValue(name)] = record.factors[name]
                }
                this[SentimentFactorDailyTable.y2Raw] = record.y2Raw
            }
        }
    }

    fun upsertCGroup(records: List<SentimentFactorDailyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.batchUpsert(records) { record ->
                this[SentimentFactorDailyTable.tradeDate] = record.tradeDate
                C_GROUP_FACTOR_NAMES.forEach { name ->
                    this[SentimentFactorDailyTable.factorColumns.getValue(name)] = record.factors[name]
                }
                this[SentimentFactorDailyTable.y3Raw] = record.y3Raw
            }
        }
    }

    fun upsert(records: List<SentimentFactorDailyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(SentimentFactorDailyTable, log = false) {
            SentimentFactorDailyTable.batchUpsert(records) { record ->
                this[SentimentFactorDailyTable.tradeDate] = record.tradeDate
                SentimentFactorDailyTable.factorColumns.forEach { (name, column) ->
                    record.factors[name]?.let { this[column] = it }
                }
                record.y1Raw?.let { this[SentimentFactorDailyTable.y1Raw] = it }
                record.y2Raw?.let { this[SentimentFactorDailyTable.y2Raw] = it }
                record.y3Raw?.let { this[SentimentFactorDailyTable.y3Raw] = it }
                record.yComposite?.let { this[SentimentFactorDailyTable.yComposite] = it }
                record.notes?.let { this[SentimentFactorDailyTable.notes] = it }
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

    private fun findStockFactsForAGroup(startDate: LocalDate, endDate: LocalDate): List<SentimentStockDailyFact> {
        val readStart = startDate.minus(DatePeriod(days = 90))
        return stockDb.transaction(StockDailyDataTable, StockBasicTable, log = false) {
            val stockInfo = StockBasicTable.selectAll()
                .associate { row ->
                    row[StockBasicTable.tsCode] to StockInfo(
                        name = row[StockBasicTable.name],
                        listDate = parseCompactDate(row[StockBasicTable.listDate]),
                        delistDate = parseCompactDate(row[StockBasicTable.delistDate]),
                    )
                }
            val rows = StockDailyDataTable.selectAll()
                .where {
                    (StockDailyDataTable.tradeDate greaterEq readStart) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                }
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .map { row ->
                    RawStockRow(
                        tradeDate = row[StockDailyDataTable.tradeDate],
                        tsCode = row[StockDailyDataTable.tsCode],
                        closeQfq = positiveOrNull(row[StockDailyDataTable.closeQfq].toDouble())
                            ?: row[StockDailyDataTable.close].toDouble(),
                        highQfq = positiveOrNull(row[StockDailyDataTable.highQfq].toDouble())
                            ?: row[StockDailyDataTable.high].toDouble(),
                        lowQfq = positiveOrNull(row[StockDailyDataTable.lowQfq].toDouble())
                            ?: row[StockDailyDataTable.low].toDouble(),
                        volumeQfq = positiveOrNull(row[StockDailyDataTable.volumeQfq].toDouble())
                            ?: row[StockDailyDataTable.volume].toDouble(),
                        turnoverReal = row[StockDailyDataTable.turnoverReal].toDouble(),
                        mvCirc = row[StockDailyDataTable.mvCirc].toDouble(),
                    )
                }

            rows.groupBy { it.tsCode }.flatMap { (tsCode, stockRows) ->
                val info = stockInfo[tsCode] ?: StockInfo(name = tsCode, listDate = null, delistDate = null)
                stockRows.zipWithNext().map { (previous, current) ->
                    SentimentStockDailyFact(
                        tradeDate = current.tradeDate,
                        tsCode = current.tsCode,
                        name = info.name,
                        listDate = info.listDate,
                        delistDate = info.delistDate,
                        closeQfq = current.closeQfq,
                        highQfq = current.highQfq,
                        lowQfq = current.lowQfq,
                        previousCloseQfq = previous.closeQfq,
                        volumeQfq = current.volumeQfq,
                        previousVolumeQfq = previous.volumeQfq,
                        turnoverReal = current.turnoverReal,
                        previousTurnoverReal = previous.turnoverReal,
                        mvCirc = current.mvCirc,
                    )
                }
            }
        }
    }

    private fun findLimitSummaries(startDate: LocalDate, endDate: LocalDate): List<SentimentLimitDailySummary> =
        stockDb.transaction(LimitListDTable, log = false) {
            LimitListDTable.selectAll()
                .where {
                    (LimitListDTable.tradeDate greaterEq startDate) and
                        (LimitListDTable.tradeDate lessEq endDate)
                }
                .map { row ->
                    LimitFact(
                        tradeDate = row[LimitListDTable.tradeDate],
                        tsCode = row[LimitListDTable.tsCode],
                        limitType = row[LimitListDTable.limitType],
                        openTimes = row[LimitListDTable.openTimes] ?: 0,
                        upStat = parseUpStat(row[LimitListDTable.upStat]),
                    )
                }
                .groupBy { it.tradeDate }
                .map { (tradeDate, rows) ->
                    val limitUpRows = rows.filter { it.limitType == "U" }
                    val consecutiveRows = limitUpRows.filter { (it.upStat ?: 0) >= 2 }
                    SentimentLimitDailySummary(
                        tradeDate = tradeDate,
                        limitUpClean = rows.count { it.limitType == "U" && it.openTimes == 0 },
                        limitUpTotal = limitUpRows.size,
                        triggered = rows.count { it.limitType == "U" || it.limitType == "Z" },
                        limitDown = rows.count { it.limitType == "D" },
                        consecutiveMax = limitUpRows.mapNotNull { it.upStat }.maxOrNull() ?: 0,
                        consecutiveCount = consecutiveRows.size,
                        limitUpTsCodes = limitUpRows.mapTo(linkedSetOf()) { it.tsCode },
                        consecutiveTsCodes = consecutiveRows.mapTo(linkedSetOf()) { it.tsCode },
                    )
                }
        }

    private fun parseUpStat(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return raw.substringBefore('/').toIntOrNull()
    }

    private fun parseCompactDate(raw: String?): LocalDate? {
        if (raw == null || raw.length != 8) return null
        return LocalDate.parse("${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}")
    }

    private fun positiveOrNull(value: Double): Double? = value.takeIf { it > 0.0 }

    private data class StockInfo(
        val name: String,
        val listDate: LocalDate?,
        val delistDate: LocalDate?,
    )

    private data class RawStockRow(
        val tradeDate: LocalDate,
        val tsCode: String,
        val closeQfq: Double,
        val highQfq: Double,
        val lowQfq: Double,
        val volumeQfq: Double,
        val turnoverReal: Double,
        val mvCirc: Double,
    )

    private data class LimitFact(
        val tradeDate: LocalDate,
        val tsCode: String,
        val limitType: String,
        val openTimes: Int,
        val upStat: Int?,
    )

    private val A_GROUP_FACTOR_NAMES = listOf(
        "A1",
        "A2",
        "A3",
        "A4",
        "A5",
        "A6",
        "A7",
        "A8",
        "A9a",
        "A9b",
        "A10",
        "A11",
        "A11a",
        "A12",
    )

    private val B_E_GROUP_FACTOR_NAMES = listOf(
        "B1",
        "B3",
        "B3p",
        "B4",
        "B5",
        "B6",
        "B7",
        "E1",
        "E2",
    )

    private val C_GROUP_FACTOR_NAMES = listOf(
        "C1",
        "C2",
        "C2p",
        "C3",
        "C4",
        "C5",
        "C6",
        "C7",
    )
}

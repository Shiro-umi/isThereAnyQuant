package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.strategy.core.daily.FactorRollingState
import org.shiroumi.database.strategy.daily.table.DailyFactorRollingStateTable
import org.shiroumi.database.transaction
import utils.logger

private val rollingStateLogger by logger("DailyFactorRollingStateRepository")
private const val ROLLING_STATE_UPSERT_BATCH_SIZE = 2_000

object DailyFactorRollingStateRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyFactorRollingStateTable, log = false) {
            DailyFactorRollingStateTable.selectAll()
                .where { DailyFactorRollingStateTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyFactorRollingStateTable, log = false) {
            DailyFactorRollingStateTable.deleteWhere { DailyFactorRollingStateTable.tradeDate eq tradeDate }
        }
    }

    fun replaceBatch(tradeDate: LocalDate, states: List<FactorRollingState>) {
        stockDb.transaction(DailyFactorRollingStateTable, log = false) {
            DailyFactorRollingStateTable.deleteWhere {
                DailyFactorRollingStateTable.tradeDate eq tradeDate
            }
        }
        if (states.isEmpty()) return

        val totalStart = System.currentTimeMillis()
        val batches = states.chunked(ROLLING_STATE_UPSERT_BATCH_SIZE)
        batches.forEachIndexed { batchIndex, batch ->
            val batchStart = System.currentTimeMillis()
            stockDb.transaction(DailyFactorRollingStateTable, log = false) {
                DailyFactorRollingStateTable.batchUpsert(batch) { s ->
                    this[DailyFactorRollingStateTable.tradeDate] = s.tradeDate
                    this[DailyFactorRollingStateTable.tsCode] = s.tsCode
                    this[DailyFactorRollingStateTable.signalBasis] = s.signalBasis
                    this[DailyFactorRollingStateTable.executionBasis] = s.executionBasis
                    this[DailyFactorRollingStateTable.requiredHistory] = s.requiredHistory
                    this[DailyFactorRollingStateTable.barsCount] = s.barsCount
                    this[DailyFactorRollingStateTable.emaShort] = s.emaShort
                    this[DailyFactorRollingStateTable.emaLong] = s.emaLong
                    this[DailyFactorRollingStateTable.atr] = s.atr
                    this[DailyFactorRollingStateTable.holding] = s.holding
                    this[DailyFactorRollingStateTable.stopPrice] = s.stopPrice
                    this[DailyFactorRollingStateTable.holdingDays] = s.holdingDays
                    this[DailyFactorRollingStateTable.shortVolumeSum] = s.shortVolumeSum
                    this[DailyFactorRollingStateTable.longVolumeSum] = s.longVolumeSum
                    this[DailyFactorRollingStateTable.prevClose] = s.prevClose
                    this[DailyFactorRollingStateTable.recentReturnsJson] = json.encodeToString(s.recentReturns)
                    this[DailyFactorRollingStateTable.recentClosesJson] = json.encodeToString(s.recentCloses)
                    this[DailyFactorRollingStateTable.recentVolumesJson] = json.encodeToString(s.recentVolumes)
                    this[DailyFactorRollingStateTable.momentumBaseClose] = s.momentumBaseClose
                }
            }
            logThroughput(
                tag = "ROLLING_STATE_UPSERT",
                rows = batch.size,
                elapsedMs = System.currentTimeMillis() - batchStart,
                batchIndex = batchIndex,
                totalBatches = batches.size,
                tradeDate = tradeDate,
            )
        }
        logThroughput(
            tag = "ROLLING_STATE_UPSERT_TOTAL",
            rows = states.size,
            elapsedMs = System.currentTimeMillis() - totalStart,
            batchIndex = 0,
            totalBatches = 1,
            tradeDate = tradeDate,
        )
    }

    private fun logThroughput(
        tag: String,
        rows: Int,
        elapsedMs: Long,
        batchIndex: Int,
        totalBatches: Int,
        tradeDate: LocalDate,
    ) {
        val rowsPerSecond = if (elapsedMs > 0) rows * 1000.0 / elapsedMs else rows.toDouble()
        rollingStateLogger.info(
            "[DB_THROUGHPUT][$tag] tradeDate=$tradeDate, rows=$rows, elapsedMs=$elapsedMs, " +
                "rowsPerSecond=${"%.2f".format(rowsPerSecond)}, batch=${batchIndex + 1}/$totalBatches"
        )
    }

    fun findByDate(tradeDate: LocalDate): List<FactorRollingState> {
        return stockDb.transaction(DailyFactorRollingStateTable, log = false) {
            DailyFactorRollingStateTable
                .selectAll()
                .where { DailyFactorRollingStateTable.tradeDate eq tradeDate }
                .map(::toState)
        }
    }

    fun findByDateAndTsCodes(tradeDate: LocalDate, tsCodes: List<String>): List<FactorRollingState> {
        if (tsCodes.isEmpty()) return emptyList()
        return stockDb.transaction(DailyFactorRollingStateTable, log = false) {
            DailyFactorRollingStateTable
                .selectAll()
                .where {
                    (DailyFactorRollingStateTable.tradeDate eq tradeDate) and
                        (DailyFactorRollingStateTable.tsCode inList tsCodes)
                }
                .map(::toState)
        }
    }

    private fun toState(row: org.jetbrains.exposed.v1.core.ResultRow): FactorRollingState {
        val context = "tradeDate=${row[DailyFactorRollingStateTable.tradeDate]}, tsCode=${row[DailyFactorRollingStateTable.tsCode]}"
        return FactorRollingState(
            tradeDate = row[DailyFactorRollingStateTable.tradeDate],
            tsCode = row[DailyFactorRollingStateTable.tsCode],
            signalBasis = row[DailyFactorRollingStateTable.signalBasis],
            executionBasis = row[DailyFactorRollingStateTable.executionBasis],
            requiredHistory = row[DailyFactorRollingStateTable.requiredHistory],
            barsCount = row[DailyFactorRollingStateTable.barsCount],
            emaShort = row[DailyFactorRollingStateTable.emaShort],
            emaLong = row[DailyFactorRollingStateTable.emaLong],
            atr = row[DailyFactorRollingStateTable.atr],
            holding = row[DailyFactorRollingStateTable.holding],
            stopPrice = row[DailyFactorRollingStateTable.stopPrice],
            holdingDays = row[DailyFactorRollingStateTable.holdingDays],
            shortVolumeSum = row[DailyFactorRollingStateTable.shortVolumeSum],
            longVolumeSum = row[DailyFactorRollingStateTable.longVolumeSum],
            prevClose = row[DailyFactorRollingStateTable.prevClose],
            recentReturns = json.parseChecked("recent_returns_json", context, row[DailyFactorRollingStateTable.recentReturnsJson]),
            recentCloses = json.parseChecked("recent_closes_json", context, row[DailyFactorRollingStateTable.recentClosesJson]),
            recentVolumes = json.parseChecked("recent_volumes_json", context, row[DailyFactorRollingStateTable.recentVolumesJson]),
            momentumBaseClose = row[DailyFactorRollingStateTable.momentumBaseClose],
        )
    }

    private inline fun <reified T> Json.parseChecked(columnName: String, context: String, raw: String): T {
        check(raw.isNotBlank()) {
            "FactorRollingState JSON column '$columnName' is blank or empty ($context). " +
                "This indicates corrupted or incomplete state data in the database."
        }
        return decodeFromString(raw)
    }
}

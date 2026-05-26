package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.DailyStrategyAuditTable
import org.shiroumi.database.transaction
import org.shiroumi.strategy.core.audit.StrategyAuditSummary

data class BacktestStrategySignalRecord(
    val tradeDate: LocalDate,
    val tsCode: String,
    /** BUY / SELL，保持字符串以避免 database 反向依赖 backtest。 */
    val side: String,
    val reason: String,
)

object DailyStrategyAuditRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun countByDate(tradeDate: LocalDate): Long = stockDb.transaction(DailyStrategyAuditTable, log = false) {
        DailyStrategyAuditTable.selectAll()
            .where { DailyStrategyAuditTable.tradeDate eq tradeDate }
            .count()
    }

    fun deleteByDate(tradeDate: LocalDate): Int = stockDb.transaction(DailyStrategyAuditTable, log = false) {
        DailyStrategyAuditTable.deleteWhere { DailyStrategyAuditTable.tradeDate eq tradeDate }
    }

    fun replaceForDate(summary: StrategyAuditSummary) {
        stockDb.transaction(DailyStrategyAuditTable) {
            DailyStrategyAuditTable.batchUpsert(listOf(Unit)) {
                this[DailyStrategyAuditTable.tradeDate] = summary.tradeDate
                this[DailyStrategyAuditTable.universeSize] = summary.universeSize
                this[DailyStrategyAuditTable.signalPositiveCount] = summary.signalPositiveCount
                this[DailyStrategyAuditTable.selectedCount] = summary.selectedCount
                this[DailyStrategyAuditTable.emptyReason] = summary.emptyReason
                this[DailyStrategyAuditTable.newlySelectedJson] = json.encodeToString(summary.newlySelected)
                this[DailyStrategyAuditTable.droppedJson] = json.encodeToString(summary.dropped)
                this[DailyStrategyAuditTable.currentPositionsJson] = json.encodeToString(summary.currentPositions)
                this[DailyStrategyAuditTable.sentimentExposure] = summary.sentimentExposure
                this[DailyStrategyAuditTable.bullRatio] = summary.bullRatio
                this[DailyStrategyAuditTable.marketVol] = summary.marketVol
                this[DailyStrategyAuditTable.fftScore] = summary.fftScore
                this[DailyStrategyAuditTable.residualScore] = summary.residualScore
                this[DailyStrategyAuditTable.accelZ] = summary.accelZ
                this[DailyStrategyAuditTable.volZ] = summary.volZ
                this[DailyStrategyAuditTable.ratioNorm] = summary.ratioNorm
                this[DailyStrategyAuditTable.volScore] = summary.volScore
                this[DailyStrategyAuditTable.accelScore] = summary.accelScore
                this[DailyStrategyAuditTable.absoluteFloor] = summary.absoluteFloor
                this[DailyStrategyAuditTable.volCap] = summary.volCap
            }
        }
    }

    fun findByDate(tradeDate: LocalDate): StrategyAuditSummary? = stockDb.transaction(DailyStrategyAuditTable, log = false) {
        DailyStrategyAuditTable.selectAll()
            .where { DailyStrategyAuditTable.tradeDate eq tradeDate }
            .map {
                StrategyAuditSummary(
                    tradeDate = it[DailyStrategyAuditTable.tradeDate],
                    universeSize = it[DailyStrategyAuditTable.universeSize],
                    signalPositiveCount = it[DailyStrategyAuditTable.signalPositiveCount],
                    selectedCount = it[DailyStrategyAuditTable.selectedCount],
                    emptyReason = it[DailyStrategyAuditTable.emptyReason],
                    newlySelected = json.decodeStringList(it[DailyStrategyAuditTable.newlySelectedJson]),
                    dropped = json.decodeStringList(it[DailyStrategyAuditTable.droppedJson]),
                    currentPositions = json.decodeStringList(it[DailyStrategyAuditTable.currentPositionsJson]),
                    sentimentExposure = it[DailyStrategyAuditTable.sentimentExposure],
                    bullRatio = it[DailyStrategyAuditTable.bullRatio],
                    marketVol = it[DailyStrategyAuditTable.marketVol],
                    fftScore = it[DailyStrategyAuditTable.fftScore],
                    residualScore = it[DailyStrategyAuditTable.residualScore],
                    accelZ = it[DailyStrategyAuditTable.accelZ],
                    volZ = it[DailyStrategyAuditTable.volZ],
                    ratioNorm = it[DailyStrategyAuditTable.ratioNorm],
                    volScore = it[DailyStrategyAuditTable.volScore],
                    accelScore = it[DailyStrategyAuditTable.accelScore],
                    absoluteFloor = it[DailyStrategyAuditTable.absoluteFloor],
                    volCap = it[DailyStrategyAuditTable.volCap],
                )
            }
            .firstOrNull()
    }

    fun getRecentRecords(limit: Int): List<StrategyAuditSummary> = stockDb.transaction(DailyStrategyAuditTable) {
        DailyStrategyAuditTable.selectAll()
            .orderBy(DailyStrategyAuditTable.tradeDate, org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(limit)
            .map {
                StrategyAuditSummary(
                    tradeDate = it[DailyStrategyAuditTable.tradeDate],
                    universeSize = it[DailyStrategyAuditTable.universeSize],
                    signalPositiveCount = it[DailyStrategyAuditTable.signalPositiveCount],
                    selectedCount = it[DailyStrategyAuditTable.selectedCount],
                    emptyReason = it[DailyStrategyAuditTable.emptyReason],
                    newlySelected = json.decodeStringList(it[DailyStrategyAuditTable.newlySelectedJson]),
                    dropped = json.decodeStringList(it[DailyStrategyAuditTable.droppedJson]),
                    currentPositions = json.decodeStringList(it[DailyStrategyAuditTable.currentPositionsJson]),
                    sentimentExposure = it[DailyStrategyAuditTable.sentimentExposure],
                    bullRatio = it[DailyStrategyAuditTable.bullRatio],
                    marketVol = it[DailyStrategyAuditTable.marketVol],
                    fftScore = it[DailyStrategyAuditTable.fftScore],
                    residualScore = it[DailyStrategyAuditTable.residualScore],
                    accelZ = it[DailyStrategyAuditTable.accelZ],
                    volZ = it[DailyStrategyAuditTable.volZ],
                    ratioNorm = it[DailyStrategyAuditTable.ratioNorm],
                    volScore = it[DailyStrategyAuditTable.volScore],
                    accelScore = it[DailyStrategyAuditTable.accelScore],
                    absoluteFloor = it[DailyStrategyAuditTable.absoluteFloor],
                    volCap = it[DailyStrategyAuditTable.volCap],
                )
            }
    }

    fun findBacktestSignalsByDate(tradeDate: LocalDate): List<BacktestStrategySignalRecord> {
        val summary = findByDate(tradeDate) ?: return emptyList()
        return summary.newlySelected.map { tsCode ->
            BacktestStrategySignalRecord(
                tradeDate = tradeDate,
                tsCode = tsCode,
                side = "BUY",
                reason = "daily_strategy_audit newlySelected",
            )
        } + summary.dropped.map { tsCode ->
            BacktestStrategySignalRecord(
                tradeDate = tradeDate,
                tsCode = tsCode,
                side = "SELL",
                reason = "daily_strategy_audit dropped",
            )
        }
    }

    private fun Json.decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { decodeFromString<List<String>>(raw) }
            .getOrElse {
                raw.split(",").map { item -> item.trim() }.filter { item -> item.isNotBlank() }
            }
    }
}

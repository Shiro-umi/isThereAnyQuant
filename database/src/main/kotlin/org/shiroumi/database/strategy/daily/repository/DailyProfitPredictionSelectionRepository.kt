package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.DailyProfitPredictionSelectionTable
import org.shiroumi.database.transaction
import org.shiroumi.strategy.core.daily.TargetPosition

data class ProfitPredictionSelection(
    val tradeDate: LocalDate,
    val targetDate: LocalDate,
    val tsCode: String,
    val modelScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val selectionReason: String?,
    val modelId: String?,
    val candidateMode: String?,
)

object DailyProfitPredictionSelectionRepository {
    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            DailyProfitPredictionSelectionTable.selectAll()
                .where { DailyProfitPredictionSelectionTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            DailyProfitPredictionSelectionTable.deleteWhere {
                DailyProfitPredictionSelectionTable.tradeDate eq tradeDate
            }
        }
    }

    fun replaceForDate(
        tradeDate: LocalDate,
        positions: List<TargetPosition>,
    ) {
        stockDb.transaction(DailyProfitPredictionSelectionTable) {
            DailyProfitPredictionSelectionTable.batchUpsert(positions) { position ->
                this[DailyProfitPredictionSelectionTable.tradeDate] = tradeDate
                this[DailyProfitPredictionSelectionTable.targetDate] = position.targetDate
                this[DailyProfitPredictionSelectionTable.tsCode] = position.tsCode
                this[DailyProfitPredictionSelectionTable.modelId] =
                    parseSelectionReason(position.selectionReason, "profit-prediction-7pct:")?.substringBefore(':')
                this[DailyProfitPredictionSelectionTable.modelScore] = position.selectionScore
                this[DailyProfitPredictionSelectionTable.selected] = position.selected
                this[DailyProfitPredictionSelectionTable.targetWeight] = position.targetWeight
                this[DailyProfitPredictionSelectionTable.sentimentExposure] = position.sentimentExposure
                this[DailyProfitPredictionSelectionTable.selectionReason] = position.selectionReason
                this[DailyProfitPredictionSelectionTable.candidateMode] =
                    parseSelectionReason(position.selectionReason, "profit-prediction-7pct:")
                        ?.substringAfter(':', "")
                        ?.substringBefore(':')
                        ?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun findSelectionsByTradeDates(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> {
        if (tradeDates.isEmpty()) return emptyMap()
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            DailyProfitPredictionSelectionTable.selectAll()
                .where {
                    (DailyProfitPredictionSelectionTable.tradeDate inList tradeDates.distinct()) and
                        (DailyProfitPredictionSelectionTable.selected eq true)
                }
                .orderBy(DailyProfitPredictionSelectionTable.tradeDate, SortOrder.ASC)
                .orderBy(DailyProfitPredictionSelectionTable.modelScore, SortOrder.DESC)
                .orderBy(DailyProfitPredictionSelectionTable.tsCode, SortOrder.ASC)
                .map(::selectionFromRow)
                .groupBy { it.tradeDate }
        }
    }

    fun findSelectedSymbolsByTargetDate(targetDate: LocalDate): Set<String> =
        findSelectionsByTargetDate(targetDate).map { it.tsCode }.toSet()

    fun findSelectionsByTargetDate(targetDate: LocalDate): List<ProfitPredictionSelection> =
        findSelectionsByTargetDates(listOf(targetDate))[targetDate].orEmpty()

    fun findSelectionsByTargetDates(targetDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> {
        if (targetDates.isEmpty()) return emptyMap()
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            DailyProfitPredictionSelectionTable.selectAll()
                .where {
                    (DailyProfitPredictionSelectionTable.targetDate inList targetDates.distinct()) and
                        (DailyProfitPredictionSelectionTable.selected eq true)
                }
                .orderBy(DailyProfitPredictionSelectionTable.targetDate, SortOrder.ASC)
                .orderBy(DailyProfitPredictionSelectionTable.modelScore, SortOrder.DESC)
                .orderBy(DailyProfitPredictionSelectionTable.tsCode, SortOrder.ASC)
                .map(::selectionFromRow)
                .groupBy { it.targetDate }
        }
    }

    fun findTargetsByTargetDate(targetDate: LocalDate): List<ProfitPredictionSelection> =
        findTargetsByTargetDates(listOf(targetDate))[targetDate].orEmpty()

    /**
     * [findTargetsByTargetDate] 的批量版本——回测预加载用，单次查询替代逐日 N 次。
     * 不添加 `selected eq true` 过滤，返回所有行（由上层按需过滤）。
     */
    fun findTargetsByTargetDates(targetDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> {
        if (targetDates.isEmpty()) return emptyMap()
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            DailyProfitPredictionSelectionTable.selectAll()
                .where { DailyProfitPredictionSelectionTable.targetDate inList targetDates.distinct() }
                .orderBy(DailyProfitPredictionSelectionTable.targetDate, SortOrder.ASC)
                .orderBy(DailyProfitPredictionSelectionTable.modelScore, SortOrder.DESC)
                .orderBy(DailyProfitPredictionSelectionTable.tsCode, SortOrder.ASC)
                .map(::selectionFromRow)
                .groupBy { it.targetDate }
        }
    }

    private fun selectionFromRow(row: ResultRow): ProfitPredictionSelection =
        ProfitPredictionSelection(
            tradeDate = row[DailyProfitPredictionSelectionTable.tradeDate],
            targetDate = row[DailyProfitPredictionSelectionTable.targetDate],
            tsCode = row[DailyProfitPredictionSelectionTable.tsCode],
            modelScore = row[DailyProfitPredictionSelectionTable.modelScore],
            selected = row[DailyProfitPredictionSelectionTable.selected],
            targetWeight = row[DailyProfitPredictionSelectionTable.targetWeight],
            sentimentExposure = row[DailyProfitPredictionSelectionTable.sentimentExposure],
            selectionReason = row[DailyProfitPredictionSelectionTable.selectionReason],
            modelId = row[DailyProfitPredictionSelectionTable.modelId],
            candidateMode = row[DailyProfitPredictionSelectionTable.candidateMode],
        )

    private fun parseSelectionReason(selectionReason: String?, prefix: String): String? =
        selectionReason
            ?.substringAfter(prefix, missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }

    fun findDataSummary(): String {
        return stockDb.transaction(DailyProfitPredictionSelectionTable, log = false) {
            val totalRows = DailyProfitPredictionSelectionTable.selectAll().count()
            var summary = "总记录数: $totalRows\n"
            
            val sqlMinMax = "SELECT MIN(target_date) as min_date, MAX(target_date) as max_date FROM daily_profit_prediction_selection"
            exec(sqlMinMax, explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT) { rs ->
                if (rs.next()) {
                    val minDate = rs.getString("min_date")
                    val maxDate = rs.getString("max_date")
                    summary += "target_date 范围: $minDate 至 $maxDate\n"
                }
                true
            }
            
            val sqlRecent = "SELECT target_date, COUNT(*) as cnt FROM daily_profit_prediction_selection GROUP BY target_date ORDER BY target_date DESC LIMIT 10"
            summary += "最新 10 个有记录的 target_date:\n"
            exec(sqlRecent, explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT) { rs ->
                while (rs.next()) {
                    val targetDate = rs.getString("target_date")
                    val cnt = rs.getInt("cnt")
                    summary += "  $targetDate: $cnt 条记录\n"
                }
                true
            }
            summary
        }
    }
}

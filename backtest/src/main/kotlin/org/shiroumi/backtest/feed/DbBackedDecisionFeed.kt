package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository

/**
 * 从策略确认表适配回测决策。
 *
 * 主链路优先读取 `daily_profit_prediction_selection`，它是盘后确认的下一交易日目标组合事实。
 * 只有目标组合缺失时，才可选择把 `daily_strategy_audit` 中的新增/剔除列表作为显式 BUY/SELL 信号 fallback。
 */
class DbBackedDecisionFeed(
    private val dataSource: StrategyDecisionDataSource = DatabaseStrategyDecisionDataSource,
    private val includeAuditSignalsWhenTargetsMissing: Boolean = true,
    private val auditSourceDateResolver: (LocalDate) -> LocalDate = DatabaseAuditSignalDateResolver::sourceDateForTargetDate,
    private val filterSignalLimitUp: Boolean = false,
    private val limitUpChecker: (tsCode: String, tradeDate: LocalDate) -> Boolean = { _, _ -> false },
) : StrategyDecisionFeed {

    override fun decisionsFor(date: LocalDate): List<StrategyDecision> {
        val targetRows = dataSource.targetRowsFor(date)
        if (targetRows.isNotEmpty()) {
            val filteredRows = if (filterSignalLimitUp) {
                val afterFilter = targetRows
                    .filter { it.modelScore.isFinite() }
                    .filterNot { limitUpChecker(it.tsCode, it.tradeDate) }
                    .sortedWith(compareByDescending<TargetPortfolioRow> { it.modelScore }
                        .thenBy { it.tsCode })
                    .take(5)
                val finalWeight = if (afterFilter.isEmpty()) 0.0 else 1.0 / afterFilter.size
                afterFilter.map { it.copy(selected = true, targetWeight = finalWeight) }
            } else {
                targetRows.filter { it.selected && it.targetWeight > 0.0 }
            }
            val targetWeights = filteredRows
                .associate { it.tsCode to it.targetWeight.coerceIn(0.0, 1.0) }
            val exposure = targetRows.maxOf { it.sentimentExposure }.coerceIn(0.0, 1.0)
            return listOf(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = date,
                    reason = targetReason(date, filteredRows),
                    targetWeights = targetWeights,
                    sentimentExposure = exposure,
                )
            )
        }

        if (!includeAuditSignalsWhenTargetsMissing) return emptyList()
        val auditTradeDate = auditSourceDateResolver(date)
        return dataSource.auditSignalsFor(auditTradeDate).mapNotNull { row ->
            val side = when (row.side.uppercase()) {
                "BUY" -> Side.BUY
                "SELL" -> Side.SELL
                else -> return@mapNotNull null
            }
            StrategyDecision.TradeIntentDecision(
                effectiveDate = date,
                reason = auditReason(row),
                tsCode = row.tsCode,
                side = side,
                hint = ExecutionHint.OPEN,
            )
        }
    }

    private fun targetReason(date: LocalDate, rows: List<TargetPortfolioRow>): String {
        val metadata = rows.flatMap { DecisionFieldSanitizer.sanitizeMetadata(it.metadata).entries }
            .joinToString(separator = ";") { "${it.key}=${it.value}" }
        val base = "daily_profit_prediction_selection targetDate=$date rows=${rows.size}"
        return if (metadata.isBlank()) base else "$base $metadata"
    }

    private fun auditReason(row: AuditSignalRow): String {
        val metadata = DecisionFieldSanitizer.sanitizeMetadata(row.metadata)
            .entries
            .joinToString(separator = ";") { "${it.key}=${it.value}" }
        return if (metadata.isBlank()) row.reason else "${row.reason} $metadata"
    }
}

interface StrategyDecisionDataSource {
    fun targetRowsFor(date: LocalDate): List<TargetPortfolioRow>
    fun auditSignalsFor(date: LocalDate): List<AuditSignalRow>
}

data class TargetPortfolioRow(
    val tradeDate: LocalDate,
    val targetDate: LocalDate,
    val tsCode: String,
    val modelScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val selectionReason: String?,
    val metadata: Map<String, String> = emptyMap(),
)

data class AuditSignalRow(
    val tradeDate: LocalDate,
    val tsCode: String,
    val side: String,
    val reason: String,
    val metadata: Map<String, String> = emptyMap(),
)

object DatabaseStrategyDecisionDataSource : StrategyDecisionDataSource {
    override fun targetRowsFor(date: LocalDate): List<TargetPortfolioRow> =
        DailyProfitPredictionSelectionRepository.findTargetsByTargetDate(date).map {
            TargetPortfolioRow(
                tradeDate = it.tradeDate,
                targetDate = it.targetDate,
                tsCode = it.tsCode,
                modelScore = it.modelScore,
                selected = it.selected,
                targetWeight = it.targetWeight,
                sentimentExposure = it.sentimentExposure,
                selectionReason = it.selectionReason,
            )
        }

    override fun auditSignalsFor(date: LocalDate): List<AuditSignalRow> =
        DailyStrategyAuditRepository.findBacktestSignalsByDate(date).map {
            AuditSignalRow(
                tradeDate = it.tradeDate,
                tsCode = it.tsCode,
                side = it.side,
                reason = it.reason,
            )
        }
}

object DatabaseAuditSignalDateResolver {
    fun sourceDateForTargetDate(targetDate: LocalDate): LocalDate =
        TradingCalendarRepository.findPreviousTradingDate(targetDate) ?: targetDate
}

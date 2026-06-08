package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection

/**
 * 全内存决策 feed 的工厂——构造时一次性批量查询全部策略决策，后续 [InMemoryDecisionFeed.decisionsFor] 纯 map 查找。
 *
 * 消除逐日 DB 查询瓶颈：250 个交易日从 ~250 次 DB 查询压缩为构造时 1-2 次批量查询。
 */
object PreloadedDecisionFeed {

    /**
     * 从 stock_db 批量预加载 [executionDates] 区间内全部策略决策。
     *
     * @param executionDates 回测执行日列表
     * @param filterSignalLimitUp 是否过滤选出日涨停的股票并取 Top5 重新等权
     * @param limitUpSymbols 若 [filterSignalLimitUp] 为 true，需传入各交易日的涨停股票集合
     * @param includeAuditFallback 目标组合缺失时是否降级读取 audit 信号
     */
    fun fromDatabase(
        executionDates: List<LocalDate>,
        filterSignalLimitUp: Boolean = false,
        limitUpSymbols: (LocalDate) -> Set<String> = { emptySet() },
        includeAuditFallback: Boolean = true,
    ): InMemoryDecisionFeed {
        // 批量查询全部目标组合
        val allRowsByDate = DailyProfitPredictionSelectionRepository
            .findTargetsByTargetDates(executionDates)

        // 收集缺失目标组合的日期，用于 audit fallback
        val missingDates = if (includeAuditFallback) {
            executionDates.filter { allRowsByDate[it].isNullOrEmpty() }
        } else {
            emptyList()
        }

        // 批量查询 audit 信号（缺失日期降级）
        val auditByTargetDate = if (missingDates.isNotEmpty()) {
            preloadAuditSignals(missingDates)
        } else {
            emptyMap()
        }

        val allDecisions = mutableListOf<StrategyDecision>()

        for (date in executionDates) {
            val rows = allRowsByDate[date].orEmpty()
            if (rows.isNotEmpty()) {
                allDecisions += buildTargetDecision(date, rows, filterSignalLimitUp, limitUpSymbols)
            } else if (includeAuditFallback) {
                val auditDecisions = auditByTargetDate[date].orEmpty()
                if (auditDecisions.isNotEmpty()) {
                    allDecisions += auditDecisions
                }
            }
        }

        return InMemoryDecisionFeed(allDecisions)
    }

    // ---- 内部转换逻辑 ----

    private fun buildTargetDecision(
        date: LocalDate,
        rows: List<ProfitPredictionSelection>,
        filterSignalLimitUp: Boolean,
        limitUpSymbols: (LocalDate) -> Set<String>,
    ): StrategyDecision.TargetPortfolioDecision {
        val mappedRows = rows.map { it.toRow() }

        val filteredRows = if (filterSignalLimitUp) {
            val limitUpSet = limitUpSymbols(date)
            val afterFilter = mappedRows
                .filter { it.modelScore.isFinite() }
                .filterNot { it.tsCode in limitUpSet }
                .sortedWith(
                    compareByDescending<TargetPortfolioRow> { it.modelScore }
                        .thenBy { it.tsCode }
                )
                .take(5)
            val finalWeight = if (afterFilter.isEmpty()) 0.0 else 1.0 / afterFilter.size
            afterFilter.map { it.copy(selected = true, targetWeight = finalWeight) }
        } else {
            mappedRows.filter { it.selected && it.targetWeight > 0.0 }
        }

        val targetWeights = filteredRows
            .associate { it.tsCode to it.targetWeight.coerceIn(0.0, 1.0) }
        val exposure = mappedRows.maxOf { it.sentimentExposure }.coerceIn(0.0, 1.0)
        val reason = "daily_profit_prediction_selection targetDate=${date} rows=${filteredRows.size}"

        return StrategyDecision.TargetPortfolioDecision(
            effectiveDate = date,
            reason = reason,
            targetWeights = targetWeights,
            sentimentExposure = exposure,
        )
    }

    private fun ProfitPredictionSelection.toRow() = TargetPortfolioRow(
        tradeDate = tradeDate,
        targetDate = targetDate,
        tsCode = tsCode,
        modelScore = modelScore,
        selected = selected,
        targetWeight = targetWeight,
        sentimentExposure = sentimentExposure,
        selectionReason = selectionReason,
    )

    private fun preloadAuditSignals(
        targetDates: List<LocalDate>,
    ): Map<LocalDate, List<StrategyDecision>> {
        val result = mutableMapOf<LocalDate, List<StrategyDecision>>()
        for (targetDate in targetDates) {
            val auditTradeDate = TradingCalendarRepository.findPreviousTradingDate(targetDate) ?: targetDate
            val signals = DailyStrategyAuditRepository.findBacktestSignalsByDate(auditTradeDate)
                .mapNotNull { row ->
                    val side = when (row.side.uppercase()) {
                        "BUY" -> Side.BUY
                        "SELL" -> Side.SELL
                        else -> return@mapNotNull null
                    }
                    StrategyDecision.TradeIntentDecision(
                        effectiveDate = targetDate,
                        reason = row.reason,
                        tsCode = row.tsCode,
                        side = side,
                        hint = ExecutionHint.OPEN,
                    )
                }
            if (signals.isNotEmpty()) {
                result[targetDate] = signals
            }
        }
        return result
    }
}

package org.shiroumi.strategy.core.audit

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition

object StrategyAuditGenerator {
    fun generate(
        tradeDate: LocalDate,
        universeSize: Int,
        factors: List<StockFactorSnapshot>,
        sentiment: MarketSentimentSnapshot,
        targets: List<TargetPosition>,
        previousCurrentPositions: Set<String>,
        currentPositions: Set<String>,
    ): StrategyAuditSummary {
        val signalPositiveCount = factors.count { it.signal }
        val selectedTargets = targets.filter { it.selected && it.targetWeight > 0.0 }

        val newlySelected = currentPositions - previousCurrentPositions
        val dropped = previousCurrentPositions - currentPositions

        val emptyReason = when {
            sentiment.sentimentExposure == 0.0 -> "情绪仓位为0: ${sentiment.reason ?: "触发下限/高波保护"}"
            signalPositiveCount == 0 -> "所有股票均未触发EMA趋势信号"
            selectedTargets.isEmpty() -> "选股后全军覆没(可能是代码异常或无足够评分)"
            else -> null
        }

        return StrategyAuditSummary(
            tradeDate = tradeDate,
            universeSize = universeSize,
            signalPositiveCount = signalPositiveCount,
            selectedCount = selectedTargets.size,
            emptyReason = emptyReason,
            newlySelected = newlySelected.sorted(),
            dropped = dropped.sorted(),
            currentPositions = currentPositions.sorted(),
            sentimentExposure = sentiment.sentimentExposure,
            bullRatio = sentiment.bullRatio,
            marketVol = sentiment.marketVol,
            fftScore = sentiment.fftScore,
            residualScore = sentiment.residualScore,
            accelZ = sentiment.accelZ,
            volZ = sentiment.volZ,
            ratioNorm = sentiment.ratioNorm,
            volScore = sentiment.volScore,
            accelScore = sentiment.accelScore,
            absoluteFloor = sentiment.absoluteFloor,
            volCap = sentiment.volCap,
        )
    }
}

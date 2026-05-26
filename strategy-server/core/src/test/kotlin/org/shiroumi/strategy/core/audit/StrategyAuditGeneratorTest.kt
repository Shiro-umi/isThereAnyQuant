package org.shiroumi.strategy.core.audit

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.daily.TargetPortfolioGenerator

class StrategyAuditGeneratorTest {

    private val targetDate = LocalDate(2026, 3, 28)

    @Test
    @DisplayName("生成审计：应识别新增与剔除，及正确理由")
    fun testAuditGeneration() {
        val tradeDate = LocalDate(2026, 3, 27)

        val factors = (1..15).map { idx ->
            StockFactorSnapshot(
                tradeDate = tradeDate,
                tsCode = "600${idx.toString().padStart(3, '0')}.SH",
                signalBasis = "HFQ",
                executionBasis = "RAW",
                sufficientHistory = true,
                requiredHistory = 400,
                open = 10.0, high = 11.0, low = 9.0, close = 10.5, volume = 1000.0,
                executionOpen = 10.0, executionClose = 10.5, hfqFactor = 1.0,
                ema10 = 10.0, ema30 = 9.0, emaBull = true, atr14 = 0.5,
                signal = idx <= 12, momentum20 = 0.1, volRatio520 = 1.2, amomCombined = 0.5,
                rankScore = 0.1 * idx
            )
        }

        val sentiment = MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = "HFQ",
            sampleSize = 500,
            bullRatio = 0.8,
            fftScore = 0.8,
            residualScore = 0.8,
            marketVol = 0.01,
            volZ = 0.0,
            accelZ = 0.5,
            sentimentExposure = 0.5,
            ratioNorm = 0.5,
            volScore = 1.0,
            accelScore = 0.5,
            absoluteFloor = 0.2,
            volCap = 1.0,
            sufficientHistory = true,
            requiredHistory = 400,
            reason = null,
        )

        val targets = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)
        val currentPositions = setOf("600010.SH", "600011.SH", "600012.SH", "600013.SH", "600014.SH")

        val auditSummary = StrategyAuditGenerator.generate(
            tradeDate = tradeDate,
            universeSize = 500,
            factors = factors,
            sentiment = sentiment,
            targets = targets,
            previousCurrentPositions = setOf("600001.SH", "600002.SH", "600999.SH"),
            currentPositions = currentPositions,
        )

        assertEquals(500, auditSummary.universeSize)
        assertEquals(12, auditSummary.signalPositiveCount)
        assertEquals(5, auditSummary.selectedCount)

        val newly = auditSummary.newlySelected
        val dropped = auditSummary.dropped
        val current = auditSummary.currentPositions

        assertTrue(auditSummary.emptyReason == null)
        assertEquals(currentPositions.size, current.size)
        assertEquals(currentPositions, current.toSet())
        assertEquals(currentPositions.size, newly.size)
        assertTrue(current.size == 5)
        assertTrue(dropped.contains("600999.SH"), "原持仓未被选入应标记为 dropped")
    }

    @Test
    @DisplayName("生成审计：情绪为0时应报告正确原因")
    fun testAuditEmptyReason() {
        val tradeDate = LocalDate(2026, 3, 27)

        val factors = emptyList<StockFactorSnapshot>()

        val sentiment = MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = "HFQ",
            sampleSize = 500,
            bullRatio = 0.1,
            fftScore = 0.1,
            residualScore = 0.1,
            marketVol = 0.01,
            volZ = 2.0,
            accelZ = -0.5,
            sentimentExposure = 0.0,
            ratioNorm = 0.0,
            volScore = 1.0,
            accelScore = 0.0,
            absoluteFloor = 0.2,
            volCap = 1.0,
            sufficientHistory = true,
            requiredHistory = 400,
            reason = "触发水线",
        )

        val targets = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)

        val auditSummary = StrategyAuditGenerator.generate(
            tradeDate = tradeDate,
            universeSize = 500,
            factors = factors,
            sentiment = sentiment,
            targets = targets,
            previousCurrentPositions = emptySet(),
            currentPositions = emptySet(),
        )

        assertTrue(auditSummary.emptyReason?.contains("情绪仓位为0") == true)
        assertTrue(auditSummary.emptyReason?.contains("触发水线") == true)
    }
}

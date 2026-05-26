package org.shiroumi.strategy.core.daily

import org.shiroumi.strategy.core.daily.*

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.intraday.IntradayPortfolioGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TargetPortfolioGeneratorTest {

    private val targetDate = LocalDate(2026, 3, 28)

    @Test
    @DisplayName("目标生成：情绪仓位应均匀分配到被选股票")
    fun testTargetWeightAllocation() {
        val tradeDate = LocalDate(2026, 3, 27)
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

        // 模拟15只触发信号的股票
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
                signal = true, momentum20 = 0.1 * idx, volRatio520 = 1.2, amomCombined = 0.5,
                rankScore = 0.1 * idx // 排名：编号越大的得分越高
            )
        }

        val targets = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)

        assertEquals(5, targets.size)

        // 应该只选前5只（TOP_N = 5）
        val selected = targets.filter { it.selected }
        assertEquals(5, selected.size)

        // 情绪仓位为 0.5，应平分给5只股票 -> 0.1
        selected.forEach {
            assertEquals(0.1, it.targetWeight, 1e-9)
            assertEquals(0.5, it.sentimentExposure, 1e-9)
            assertEquals(targetDate, it.targetDate)
        }
    }

    @Test
    @DisplayName("目标生成：无信号时不应生成选股")
    fun testNoSignalAllocation() {
        val tradeDate = LocalDate(2026, 3, 27)
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
            sentimentExposure = 1.0,
            ratioNorm = 0.8,
            volScore = 1.0,
            accelScore = 0.5,
            absoluteFloor = 0.2,
            volCap = 1.0,
            sufficientHistory = true,
            requiredHistory = 400,
            reason = null,
        )

        // 模拟信号全无
        val factors = (1..5).map { idx ->
            StockFactorSnapshot(
                tradeDate = tradeDate,
                tsCode = "600${idx.toString().padStart(3, '0')}.SH",
                signalBasis = "HFQ",
                executionBasis = "RAW",
                sufficientHistory = true,
                requiredHistory = 400,
                open = 10.0, high = 11.0, low = 9.0, close = 10.5, volume = 1000.0,
                executionOpen = 10.0, executionClose = 10.5, hfqFactor = 1.0,
                ema10 = 9.0, ema30 = 10.0, emaBull = false, atr14 = 0.5,
                signal = false, momentum20 = -0.1, volRatio520 = 0.8, amomCombined = -0.5,
                rankScore = 0.2
            )
        }

        val targets = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)
        val selected = targets.filter { it.selected }
        assertTrue(selected.isEmpty())

        targets.forEach {
            assertEquals(0.0, it.targetWeight, 1e-9)
            assertTrue(it.selectionReason?.contains("未触发") == true)
        }
    }

    @Test
    @DisplayName("目标生成：情绪仓位为0时仍应保留当天选股结果，但不实际买入")
    fun testExposureZeroKeepsSelections() {
        val tradeDate = LocalDate(2026, 3, 27)
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

        val factors = (1..10).map { idx ->
            StockFactorSnapshot(
                tradeDate = tradeDate,
                tsCode = "000${idx.toString().padStart(3, '0')}.SZ",
                signalBasis = "HFQ",
                executionBasis = "RAW",
                sufficientHistory = true,
                requiredHistory = 400,
                open = 10.0,
                high = 11.0,
                low = 9.0,
                close = 10.5,
                volume = 1000.0,
                executionOpen = 10.0,
                executionClose = 10.5,
                hfqFactor = 1.0,
                ema10 = 10.0,
                ema30 = 9.0,
                emaBull = true,
                atr14 = 0.5,
                signal = true,
                momentum20 = 0.1 * idx,
                volRatio520 = 1.2,
                amomCombined = 0.5 + idx,
                rankScore = 1.0 * idx,
            )
        }

        val targets = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)

        assertEquals(5, targets.size)
        assertTrue(targets.none { it.selected })
        targets.forEach {
            assertEquals(0.0, it.targetWeight, 1e-9)
            assertTrue(it.selectionReason?.contains("情绪为0") == true)
        }
    }

    @Test
    @DisplayName("盘中组合：应复用盘后横截面选股口径")
    fun testIntradayPortfolioUsesDailySelectionRules() {
        val tradeDate = LocalDate(2026, 3, 27)
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
        val factors = listOf(
            factor(tradeDate, "000001.SZ", momentum20 = 0.80, volRatio520 = 2.0, amomCombined = 2.0, rankScore = 0.10),
            factor(tradeDate, "000002.SZ", momentum20 = 0.70, volRatio520 = 1.8, amomCombined = 1.8, rankScore = 0.20),
            factor(tradeDate, "000003.SZ", momentum20 = 0.60, volRatio520 = 1.6, amomCombined = 1.6, rankScore = 0.30),
            factor(tradeDate, "000004.SZ", momentum20 = 0.50, volRatio520 = 1.4, amomCombined = 1.4, rankScore = 0.40),
            factor(tradeDate, "000005.SZ", momentum20 = 0.40, volRatio520 = 1.2, amomCombined = 1.2, rankScore = 0.50),
            factor(tradeDate, "000006.SZ", momentum20 = -0.50, volRatio520 = 0.2, amomCombined = -0.5, rankScore = 0.99),
        )

        val dailyCodes = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)
            .filter { it.selected }
            .map { it.tsCode }
        val intradayCodes = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = 0L,
            factors = factors,
            sentiment = sentiment
        ).filter { it.selected }.map { it.tsCode }

        assertEquals(dailyCodes, intradayCodes)
        assertTrue("000001.SZ" in intradayCodes)
        assertTrue("000006.SZ" !in intradayCodes)
    }

    private fun factor(
        tradeDate: LocalDate,
        tsCode: String,
        momentum20: Double,
        volRatio520: Double,
        amomCombined: Double,
        rankScore: Double
    ): StockFactorSnapshot = StockFactorSnapshot(
        tradeDate = tradeDate,
        tsCode = tsCode,
        signalBasis = "HFQ",
        executionBasis = "RAW",
        sufficientHistory = true,
        requiredHistory = 400,
        open = 10.0,
        high = 11.0,
        low = 9.0,
        close = 10.5,
        volume = 1000.0,
        executionOpen = 10.0,
        executionClose = 10.5,
        hfqFactor = 1.0,
        ema10 = 10.0,
        ema30 = 9.0,
        emaBull = true,
        atr14 = 0.5,
        signal = true,
        momentum20 = momentum20,
        volRatio520 = volRatio520,
        amomCombined = amomCombined,
        rankScore = rankScore,
    )
}

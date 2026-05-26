package org.shiroumi.database.strategy.intraday

import org.shiroumi.strategy.core.daily.*
import org.shiroumi.strategy.core.intraday.*

import kotlinx.datetime.LocalDate
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.exp
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.sentiment.SentimentRuntimeMath

class IntradaySentimentCalculatorTest {

    @Test
    fun `accel seed resumes continuous formula without drift`() {
        val historicalBullRatio = (0 until 400).map { index ->
            0.38 + sin(index / 11.0) * 0.09 + (index % 7) * 0.002
        }
        val historicalMarketVol = (0 until 400).map { index ->
            0.015 + (index % 13) * 0.0007
        }
        val tradeDate = LocalDate(2026, 4, 8)
        val sampleCodes = (1..10).map { "00000${it}.SZ" }
        val intradayFactors = sampleCodes.mapIndexed { index, tsCode ->
            factor(
                tsCode = tsCode,
                bullish = index < 6,
                close = 10.0 + index,
                atr14 = 0.8 + index * 0.05
            )
        }
        val baseSentiment = sentimentSnapshot(
            tradeDate = LocalDate(2026, 4, 7),
            bullRatio = historicalBullRatio.last(),
            marketVol = historicalMarketVol.last()
        )

        val resumedFromSeed = IntradaySentimentCalculator.calculateIncremental(
            tradeDate = tradeDate,
            baseSentiment = baseSentiment,
            intradayFactors = intradayFactors,
            sampleCodes = sampleCodes,
            bullRatioHistorySeed = historicalBullRatio.takeLast(SentimentRuntimeMath.RUNTIME_WINDOW),
            marketVolHistorySeed = historicalMarketVol.takeLast(SentimentRuntimeMath.RUNTIME_WINDOW),
            accelHistorySeed = SentimentRuntimeMath.rebuildAccelWindowFromBullRatioHistory(historicalBullRatio)
        )

        val expectedAccelWindow = SentimentRuntimeMath.rebuildAccelWindowFromBullRatioHistory(
            historicalBullRatio + resumedFromSeed.bullRatio
        )
        val expectedAccelZ = latestZScore(expectedAccelWindow)

        assertEquals(0.6, resumedFromSeed.bullRatio, 1e-12)
        assertEquals(expectedAccelZ, resumedFromSeed.accelZ, 1e-12)
    }

    @Test
    fun `partial realtime sample coverage falls back to base sentiment`() {
        val tradeDate = LocalDate(2026, 4, 8)
        val sampleCodes = (1..10).map { "00000${it}.SZ" }
        val intradayFactors = listOf(
            factor("000001.SZ", bullish = true, close = 10.0, atr14 = 0.8),
            factor("000002.SZ", bullish = true, close = 11.0, atr14 = 0.9),
            factor("000003.SZ", bullish = false, close = 12.0, atr14 = 1.0)
        )
        val baseSentiment = sentimentSnapshot(
            tradeDate = LocalDate(2026, 4, 7),
            bullRatio = 0.42,
            marketVol = 0.018
        )

        val result = IntradaySentimentCalculator.calculateIncremental(
            tradeDate = tradeDate,
            baseSentiment = baseSentiment,
            intradayFactors = intradayFactors,
            sampleCodes = sampleCodes,
            bullRatioHistorySeed = List(252) { 0.4 },
            marketVolHistorySeed = List(252) { 0.018 },
            accelHistorySeed = List(252) { 0.0 }
        )

        assertEquals(tradeDate, result.tradeDate)
        assertEquals(baseSentiment.bullRatio, result.bullRatio, 1e-12)
        assertEquals(baseSentiment.marketVol, result.marketVol, 1e-12)
        assertEquals(baseSentiment.sentimentExposure, result.sentimentExposure, 1e-12)
        assertTrue(result.reason?.contains("样本覆盖不足") == true)
    }

    @Test
    fun `intraday volatility fields stay anchored to daily baseline`() {
        val tradeDate = LocalDate(2026, 4, 8)
        val sampleCodes = (1..10).map { "00000${it}.SZ" }
        val intradayFactors = sampleCodes.mapIndexed { index, tsCode ->
            factor(
                tsCode = tsCode,
                bullish = index < 7,
                close = 10.0 + index,
                atr14 = 1.6 + index * 0.1
            )
        }
        val baseSentiment = sentimentSnapshot(
            tradeDate = LocalDate(2026, 4, 7),
            bullRatio = 0.48,
            marketVol = 0.018
        ).copy(
            volZ = 0.35,
            volScore = 0.41,
            volCap = 0.92
        )

        val result = IntradaySentimentCalculator.calculateIncremental(
            tradeDate = tradeDate,
            baseSentiment = baseSentiment,
            intradayFactors = intradayFactors,
            sampleCodes = sampleCodes,
            bullRatioHistorySeed = List(252) { 0.47 },
            marketVolHistorySeed = List(252) { 0.018 },
            accelHistorySeed = List(252) { 0.0 }
        )

        assertEquals(baseSentiment.marketVol, result.marketVol, 1e-12)
        assertEquals(baseSentiment.volZ, result.volZ, 1e-12)
        assertEquals(1.0 / (1.0 + exp(baseSentiment.volZ)), result.volScore, 1e-12)
        assertEquals(1.0, result.volCap, 1e-12)
    }

    private fun sentimentSnapshot(
        tradeDate: LocalDate,
        bullRatio: Double,
        marketVol: Double
    ): MarketSentimentSnapshot {
        return MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = "HFQ",
            sampleSize = 10,
            bullRatio = bullRatio,
            fftScore = 0.5,
            residualScore = 0.5,
            marketVol = marketVol,
            volZ = 0.2,
            accelZ = 0.1,
            sentimentExposure = 0.5,
            ratioNorm = 0.5,
            volScore = 0.5,
            accelScore = 0.5,
            absoluteFloor = 1.0,
            volCap = 1.0,
            sufficientHistory = true,
            requiredHistory = 400,
            reason = null
        )
    }

    private fun factor(
        tsCode: String,
        bullish: Boolean,
        close: Double,
        atr14: Double
    ): StockFactorSnapshot {
        return StockFactorSnapshot(
            tradeDate = LocalDate(2026, 4, 8),
            tsCode = tsCode,
            signalBasis = "HFQ",
            executionBasis = "RAW",
            sufficientHistory = true,
            requiredHistory = 400,
            open = close,
            high = close,
            low = close,
            close = close,
            volume = 1000.0,
            executionOpen = close,
            executionClose = close,
            hfqFactor = 1.0,
            ema10 = close,
            ema30 = if (bullish) close - 0.1 else close + 0.1,
            emaBull = bullish,
            atr14 = atr14,
            signal = bullish,
            momentum20 = 0.2,
            volRatio520 = 1.1,
            amomCombined = 0.3,
            rankScore = 0.4
        )
    }

    private fun latestZScore(values: List<Double>): Double {
        if (values.size < 60) return 0.0
        val mean = values.average()
        var variance = 0.0
        values.forEach { value ->
            val diff = value - mean
            variance += diff * diff
        }
        val std = kotlin.math.sqrt(variance / values.size)
        return if (std > 1e-8) {
            (values.last() - mean) / std
        } else {
            0.0
        }
    }
}

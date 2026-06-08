package org.shiroumi.strategy.core.intraday

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition

class IntradayPortfolioGeneratorTest {
    private val tradeDate = LocalDate(2026, 4, 30)

    @Test
    fun `keeps post-market model selections visible when sentiment exposure is zero`() {
        val positions = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = 0L,
            factors = emptyList(),
            sentiment = sentiment(exposure = 0.0),
            postMarketPositions = listOf(
                target("000001.SZ", 0.93),
                target("000002.SZ", 0.91),
            )
        )

        assertEquals(listOf("000001.SZ", "000002.SZ"), positions.map { it.tsCode })
        assertEquals(listOf(true, true), positions.map { it.selected })
        assertEquals(listOf(0.5, 0.5), positions.map { it.targetWeight })
    }

    private fun target(tsCode: String, score: Double) = TargetPosition(
        tradeDate = tradeDate,
        targetDate = LocalDate(2026, 5, 6),
        tsCode = tsCode,
        selectionScore = score,
        selected = true,
        targetWeight = 0.5,
        sentimentExposure = 0.0,
        selectionReason = "profit-prediction-7pct:unit:all-universe:Top2",
    )

    private fun sentiment(exposure: Double) = MarketSentimentSnapshot(
        tradeDate = tradeDate,
        signalBasis = "HFQ",
        sampleSize = 3,
        bullRatio = 0.1,
        fftScore = 0.0,
        residualScore = 0.0,
        marketVol = 0.02,
        volZ = 2.0,
        accelZ = -1.0,
        sentimentExposure = exposure,
        ratioNorm = 0.0,
        volScore = 0.0,
        accelScore = 0.0,
        absoluteFloor = 0.256,
        volCap = 1.0,
        sufficientHistory = true,
        requiredHistory = 252,
        reason = "absolute floor",
    )
}

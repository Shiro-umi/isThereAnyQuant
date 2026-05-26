package org.shiroumi.strategy.core.daily

import org.shiroumi.strategy.core.daily.*

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import model.PriceBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar

class MarketSentimentBehaviorTest {

    @Test
    @DisplayName("主板全市场全部走弱时 exposure 应接近 0")
    fun testWeakMarketExposure() {
        val barsBySymbol = mapOf(
            "600000.SH" to descendingBars("600000.SH", 400),
            "000001.SZ" to descendingBars("000001.SZ", 400),
            "601318.SH" to descendingBars("601318.SH", 400),
        )

        val snapshot = MarketSentimentCalculator.calculate(barsBySymbol, requiredHistory = 300)
        assertTrue(snapshot.sufficientHistory)
        assertTrue(snapshot.bullRatio < 0.2)
        assertTrue(snapshot.sentimentExposure < 0.2)
    }

    @Test
    @DisplayName("主板全市场一致上行时 exposure 应大于弱市")
    fun testStrongMarketExposure() {
        val weak = MarketSentimentCalculator.calculate(
            mapOf(
                "600000.SH" to descendingBars("600000.SH", 400),
                "000001.SZ" to descendingBars("000001.SZ", 400),
            ),
            requiredHistory = 300,
        )
        val strong = MarketSentimentCalculator.calculate(
            mapOf(
                "600000.SH" to ascendingBars("600000.SH", 400),
                "000001.SZ" to ascendingBars("000001.SZ", 400),
            ),
            requiredHistory = 300,
        )

        assertTrue(strong.sufficientHistory)
        assertTrue(strong.bullRatio > weak.bullRatio)
        assertTrue(strong.sentimentExposure > weak.sentimentExposure)
    }

    private fun ascendingBars(tsCode: String, count: Int): List<PreparedBar> = bars(tsCode, count) { 8.0 + it * 0.05 }
    private fun descendingBars(tsCode: String, count: Int): List<PreparedBar> = bars(tsCode, count) { 30.0 - it * 0.05 }

    private fun bars(tsCode: String, count: Int, priceAt: (Int) -> Double): List<PreparedBar> {
        val start = LocalDate(2023, 1, 1)
        return (0 until count).map { idx ->
            val close = priceAt(idx)
            val date = start.plus(DatePeriod(days = idx))
            PreparedBar(
                tsCode = tsCode,
                date = date,
                signalBasis = PriceBasis.HFQ,
                executionBasis = PriceBasis.RAW,
                open = close * 0.99,
                high = close * 1.01,
                low = close * 0.98,
                close = close,
                volume = 10000.0 + idx,
                executionOpen = close * 0.98,
                executionClose = close * 0.97,
                rawOpen = close * 0.98,
                rawHigh = close,
                rawLow = close * 0.96,
                rawClose = close * 0.97,
                rawVolume = 12000.0,
                qfqOpen = close * 0.97,
                qfqHigh = close * 0.99,
                qfqLow = close * 0.95,
                qfqClose = close * 0.96,
                qfqVolume = 11000.0,
                hfqFactor = 1.2,
            )
        }
    }
}

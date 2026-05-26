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

class MarketSentimentCalculatorTest {

    @Test
    @DisplayName("市场情绪在主板全样本上应输出有效快照")
    fun testCalculateSnapshot() {
        val barsBySymbol = mapOf(
            "600000.SH" to createBars("600000.SH", 1.0, 400),
            "000001.SZ" to createBars("000001.SZ", 0.8, 400),
            "601318.SH" to createBars("601318.SH", 1.2, 400),
        )

        val snapshot = MarketSentimentCalculator.calculate(
            barsBySymbol = barsBySymbol,
            requiredHistory = 300,
        )

        assertTrue(snapshot.sufficientHistory)
        assertEquals(3, snapshot.sampleSize)
        assertTrue(snapshot.sentimentExposure in 0.0..1.0)
        assertTrue(snapshot.bullRatio in 0.0..1.0)
        assertEquals("HFQ", snapshot.signalBasis)
    }

    @Test
    @DisplayName("共同交易日不足时应返回历史不足")
    fun testInsufficientHistory() {
        val barsBySymbol = mapOf(
            "600000.SH" to createBars("600000.SH", 1.0, 120),
            "000001.SZ" to createBars("000001.SZ", 0.8, 120),
        )

        val snapshot = MarketSentimentCalculator.calculate(
            barsBySymbol = barsBySymbol,
            requiredHistory = 300,
        )

        assertFalse(snapshot.sufficientHistory)
        assertTrue(snapshot.reason?.contains("共同交易日不足") == true)
        assertEquals(0.0, snapshot.sentimentExposure, 1e-9)
    }

    private fun createBars(tsCode: String, slope: Double, count: Int): List<PreparedBar> {
        val start = LocalDate(2023, 1, 1)
        return (0 until count).map { index ->
            val price = 10.0 + index * 0.03 * slope + kotlin.math.sin(index / 9.0) * 0.4
            val date = start.plus(DatePeriod(days = index))
            PreparedBar(
                tsCode = tsCode,
                date = date,
                signalBasis = PriceBasis.HFQ,
                executionBasis = PriceBasis.RAW,
                open = price * 0.99,
                high = price * 1.01,
                low = price * 0.98,
                close = price,
                volume = 10000.0 + index * 10,
                executionOpen = price * 0.97,
                executionClose = price * 0.98,
                rawOpen = price * 0.97,
                rawHigh = price * 0.99,
                rawLow = price * 0.95,
                rawClose = price * 0.98,
                rawVolume = 12000.0,
                qfqOpen = price * 0.96,
                qfqHigh = price,
                qfqLow = price * 0.94,
                qfqClose = price * 0.97,
                qfqVolume = 11000.0,
                hfqFactor = 1.2,
            )
        }
    }
}

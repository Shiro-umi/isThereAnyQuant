package org.shiroumi.strategy.core.daily

import org.shiroumi.strategy.core.daily.*

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import model.PriceBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedStockWindow

class StockFactorCalculatorTest {

    @Test
    @DisplayName("历史不足时应返回 null")
    fun testInsufficientHistory() {
        val window = PreparedStockWindow(
            tsCode = "600000.SH",
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
            bars = emptyList(),
            sufficientHistory = false,
            requiredHistory = 400,
        )
        assertNull(StockFactorCalculator.calculate(window))
    }

    @Test
    @DisplayName("因子计算：EMA与EMA_BULL应正确反映趋势")
    fun testTrendFactors() {
        val window = ascendingWindow("600000.SH", 400)
        val snapshot = StockFactorCalculator.calculate(window)

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.ema10 > snapshot.ema30, "上升趋势中 EMA10 应大于 EMA30")
        assertTrue(snapshot.emaBull, "上升趋势应触发 EMA_BULL")
        assertTrue(snapshot.signal, "上升趋势且历史足够应触发做多信号")
    }

    @Test
    @DisplayName("因子计算：弱势下应无信号")
    fun testDowntrendFactors() {
        val window = descendingWindow("600000.SH", 400)
        val snapshot = StockFactorCalculator.calculate(window)

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.ema10 < snapshot.ema30, "下降趋势中 EMA10 应小于 EMA30")
        assertFalse(snapshot.emaBull, "下降趋势不应触发 EMA_BULL")
        assertFalse(snapshot.signal, "下降趋势应无做多信号")
    }

    @Test
    @DisplayName("打分验证：强趋势+高动量得分应高于弱势")
    fun testRankScore() {
        val strong = StockFactorCalculator.calculate(ascendingWindow("600000.SH", 400))!!
        val weak = StockFactorCalculator.calculate(descendingWindow("600001.SH", 400))!!

        assertTrue(strong.momentum20 > weak.momentum20)
        assertTrue(strong.rankScore > weak.rankScore, "强势股总得分应高于弱势股")
    }

    private fun ascendingWindow(tsCode: String, count: Int): PreparedStockWindow =
        createWindow(tsCode, count) { 10.0 + it * 0.05 }

    private fun descendingWindow(tsCode: String, count: Int): PreparedStockWindow =
        createWindow(tsCode, count) { 30.0 - it * 0.05 }

    private fun createWindow(tsCode: String, count: Int, priceAt: (Int) -> Double): PreparedStockWindow {
        val start = LocalDate(2023, 1, 1)
        val bars = (0 until count).map { idx ->
            val price = priceAt(idx)
            val date = start.plus(DatePeriod(days = idx))
            PreparedBar(
                tsCode = tsCode,
                date = date,
                signalBasis = PriceBasis.HFQ,
                executionBasis = PriceBasis.RAW,
                open = price * 0.99,
                high = price * 1.01,
                low = price * 0.98,
                close = price,
                volume = 10000.0 + idx,
                executionOpen = price * 0.98,
                executionClose = price * 0.97,
                rawOpen = price * 0.98,
                rawHigh = price,
                rawLow = price * 0.96,
                rawClose = price * 0.97,
                rawVolume = 12000.0,
                qfqOpen = price * 0.97,
                qfqHigh = price * 0.99,
                qfqLow = price * 0.95,
                qfqClose = price * 0.96,
                qfqVolume = 11000.0,
                hfqFactor = 1.0,
            )
        }
        return PreparedStockWindow(
            tsCode = tsCode,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
            bars = bars,
            sufficientHistory = true,
            requiredHistory = 400,
        )
    }
}

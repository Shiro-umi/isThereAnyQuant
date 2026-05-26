package org.shiroumi.strategy.core.daily

import org.shiroumi.strategy.core.daily.*

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import model.PriceBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedStockWindow
import kotlin.math.abs

class StockFactorCalculatorIncrementalTest {

    @Test
    @DisplayName("增量递推结果应与全量计算完全等价")
    fun testIncrementalMatchesFull() {
        val tsCode = "688001.SH"
        val totalDays = 400
        val bars = createBars(tsCode, totalDays)

        // 1. 全量计算
        val fullWindow = PreparedStockWindow(
            tsCode = tsCode,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
            bars = bars,
            sufficientHistory = true,
            requiredHistory = totalDays,
        )
        val fullSnapshot = StockFactorCalculator.calculate(fullWindow)!!

        // 2. 增量计算：先跑前 200 天获得 state，再逐日递推
        val splitIndex = 200
        val headWindow = PreparedStockWindow(
            tsCode = tsCode,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
            bars = bars.take(splitIndex),
            sufficientHistory = true,
            requiredHistory = totalDays,
        )
        val headFullSnapshot = StockFactorCalculator.calculate(headWindow)!!
        val (state, _) = StockFactorCalculator.process(headWindow)!!

        var currentState = state
        var incrementalSnapshot: StockFactorSnapshot? = null
        bars.drop(splitIndex).forEach { bar ->
            val (nextState, snap) = StockFactorCalculator.calculate(currentState, bar)!!
            currentState = nextState
            incrementalSnapshot = snap
        }

        // 3. 比对全量与增量的最终 snapshot
        assertSnapshotEquals(fullSnapshot, incrementalSnapshot!!)
    }

    private fun assertSnapshotEquals(expected: StockFactorSnapshot, actual: StockFactorSnapshot) {
        assertDoubleEquals(expected.ema10, actual.ema10, "ema10")
        assertDoubleEquals(expected.ema30, actual.ema30, "ema30")
        assertEquals(expected.emaBull, actual.emaBull, "emaBull")
        assertDoubleEquals(expected.atr14, actual.atr14, "atr14")
        assertEquals(expected.signal, actual.signal, "signal")
        assertDoubleEquals(expected.momentum20, actual.momentum20, "momentum20")
        assertDoubleEquals(expected.volRatio520, actual.volRatio520, "volRatio520")
        assertDoubleEquals(expected.amomCombined, actual.amomCombined, "amomCombined")
        assertDoubleEquals(expected.rankScore, actual.rankScore, "rankScore")
        assertEquals(expected.tsCode, actual.tsCode)
        assertEquals(expected.tradeDate, actual.tradeDate)
        assertDoubleEquals(expected.close, actual.close, "close")
    }

    private fun assertDoubleEquals(expected: Double, actual: Double, field: String) {
        val tolerance = 1e-9
        if (abs(expected - actual) > tolerance) {
            throw AssertionError("$field mismatch: expected=$expected, actual=$actual, diff=${abs(expected - actual)}")
        }
    }

    private fun createBars(tsCode: String, count: Int): List<PreparedBar> {
        val start = LocalDate(2023, 1, 1)
        return (0 until count).map { idx ->
            val close = 10.0 + idx * 0.03 + (if (idx % 7 == 0) 0.2 else 0.0)
            val open = close * (0.99 + (idx % 5) * 0.001)
            val high = close * 1.015
            val low = close * 0.985
            val volume = 10000.0 + idx * 10 + (if (idx % 3 == 0) 5000.0 else 0.0)
            PreparedBar(
                tsCode = tsCode,
                date = start.plus(DatePeriod(days = idx)),
                signalBasis = PriceBasis.HFQ,
                executionBasis = PriceBasis.RAW,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                executionOpen = open * 0.98,
                executionClose = close * 0.97,
                rawOpen = open,
                rawHigh = high,
                rawLow = low,
                rawClose = close,
                rawVolume = volume,
                qfqOpen = open * 0.99,
                qfqHigh = high * 1.01,
                qfqLow = low * 0.99,
                qfqClose = close * 0.98,
                qfqVolume = volume * 1.1,
                hfqFactor = 1.0,
            )
        }
    }
}

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
import kotlin.math.abs

class MarketSentimentCalculatorIncrementalTest {

    @Test
    @DisplayName("市场情绪增量递推结果应与全量计算完全等价")
    fun testIncrementalMatchesFull() {
        val symbolCount = 50
        val totalDays = 400
        val splitAt = 200
        val requiredHistory = 10 // 足够小，让 200 天 head 也能通过 sufficientHistory 检查

        val barsBySymbol = createMultiSymbolBars(symbolCount, totalDays)

        // 1. 全量计算
        val (fullState, fullSnapshot) = MarketSentimentCalculator.process(
            barsBySymbol = barsBySymbol,
            requiredHistory = requiredHistory,
        )

        // 2. 先算前 200 天拿到 state
        val headBarsBySymbol = barsBySymbol.mapValues { it.value.take(splitAt) }
        val (headState, _) = MarketSentimentCalculator.process(
            barsBySymbol = headBarsBySymbol,
            requiredHistory = requiredHistory,
        )

        // 3. 后 200 天逐日增量
        var currentState = headState
        var incrementalSnapshot: MarketSentimentSnapshot? = null
        for (dayOffset in splitAt until totalDays) {
            val tradeDate = startDate.plus(DatePeriod(days = dayOffset))
            val todayBars = barsBySymbol.mapValues { it.value[dayOffset] }
            val (nextState, snap) = MarketSentimentCalculator.calculate(
                state = currentState,
                todayBarsBySymbol = todayBars,
                tradeDate = tradeDate,
                requiredHistory = requiredHistory,
            )
            currentState = nextState
            incrementalSnapshot = snap
        }

        // 4. 比对
        assertSnapshotEquals(fullSnapshot, incrementalSnapshot!!)
        // 额外校验 state 中的历史窗口长度
        assertEquals(252, currentState.bullRatioHistory.size, "bullRatioHistory 应该被截断到 252")
        assertEquals(252, currentState.marketVolHistory.size, "marketVolHistory 应该被截断到 252")
        assertEquals(252, currentState.accelHistory.size, "accelHistory 应该被截断到 252")
        assertEquals(252, currentState.combinedHistory.size, "combinedHistory 应该被截断到 252")
        assertEquals(totalDays, currentState.totalDays, "totalDays 应该为 $totalDays")
        assertEquals(fullState.totalDays, currentState.totalDays, "totalDays 应该与全量一致")
    }

    @Test
    @DisplayName("市场情绪增量递推应逐日与全量窗口重算一致")
    fun testIncrementalMatchesFullDayByDay() {
        val symbolCount = 30
        val totalDays = 260
        val splitAt = 120
        val requiredHistory = 10
        val barsBySymbol = createMultiSymbolBars(symbolCount, totalDays)

        val headBarsBySymbol = barsBySymbol.mapValues { it.value.take(splitAt) }
        var currentState = MarketSentimentCalculator.process(
            barsBySymbol = headBarsBySymbol,
            requiredHistory = requiredHistory,
        ).first

        for (dayOffset in splitAt until totalDays) {
            val tradeDate = startDate.plus(DatePeriod(days = dayOffset))
            val todayBars = barsBySymbol.mapValues { it.value[dayOffset] }
            val (nextState, incrementalSnapshot) = MarketSentimentCalculator.calculate(
                state = currentState,
                todayBarsBySymbol = todayBars,
                tradeDate = tradeDate,
                requiredHistory = requiredHistory,
            )
            val fullSnapshot = MarketSentimentCalculator.process(
                barsBySymbol = barsBySymbol.mapValues { it.value.take(dayOffset + 1) },
                requiredHistory = requiredHistory,
            ).second

            assertSnapshotEquals(fullSnapshot, incrementalSnapshot)
            currentState = nextState
        }
    }

    @Test
    @DisplayName("rebuildSymbolState 应与全量路径中的单股状态一致")
    fun testRebuildSymbolStateMatchesProcess() {
        val totalDays = 100
        val tsCode = "000001.SZ"
        val bars = createBars(tsCode, totalDays)
        val barsBySymbol = mapOf(tsCode to bars)

        val (fullState, _) = MarketSentimentCalculator.process(
            barsBySymbol = barsBySymbol,
            requiredHistory = 10,
        )

        val rebuilt = MarketSentimentCalculator.rebuildSymbolState(tsCode, bars)
        val expected = fullState.symbolStates.first { it.tsCode == tsCode }

        assertSymbolStateEquals(expected, rebuilt)
    }

    @Test
    @DisplayName("严格 close 观测输入应与日频增量路径完全一致")
    fun testStrictObservedClosesMatchesIncremental() {
        val symbolCount = 20
        val totalDays = 180
        val splitAt = 100
        val requiredHistory = 10
        val barsBySymbol = createMultiSymbolBars(symbolCount, totalDays)
        val headBarsBySymbol = barsBySymbol.mapValues { it.value.take(splitAt) }
        val currentState = MarketSentimentCalculator.process(
            barsBySymbol = headBarsBySymbol,
            requiredHistory = requiredHistory,
        ).first
        val tradeDate = startDate.plus(DatePeriod(days = splitAt))
        val todayBars = barsBySymbol.mapValues { it.value[splitAt] }

        val expected = MarketSentimentCalculator.calculate(
            state = currentState,
            todayBarsBySymbol = todayBars,
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
        )
        val actual = MarketSentimentCalculator.calculateStrict(
            state = currentState,
            observedClosesBySymbol = todayBars.mapValues { it.value.close },
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
        )

        assertSnapshotEquals(expected.second, actual.second)
        assertEquals(expected.first.sampleCodes, actual.first.sampleCodes)
        assertEquals(expected.first.totalDays, actual.first.totalDays)
    }

    @Test
    @DisplayName("calculate 排除缺失 bar 的股票但保留其 state")
    fun testCalculateExcludesMissingBarsButPreservesState() {
        val symbolCount = 30
        val totalDays = 200
        val splitAt = 150
        val requiredHistory = 10
        val barsBySymbol = createMultiSymbolBars(symbolCount, totalDays)
        val headBarsBySymbol = barsBySymbol.mapValues { it.value.take(splitAt) }
        val currentState = MarketSentimentCalculator.process(
            barsBySymbol = headBarsBySymbol,
            requiredHistory = requiredHistory,
        ).first

        val tradeDate = startDate.plus(DatePeriod(days = splitAt))
        val allTodayBars = barsBySymbol.mapValues { it.value[splitAt] }
        // 只提供前 25 只股票的 bar，排除后 5 只
        val presentCodes = allTodayBars.keys.sorted().take(25).toSet()
        val missingCodes = allTodayBars.keys.sorted().drop(25).toSet()
        val partialTodayBars = allTodayBars.filterKeys { presentCodes.contains(it) }

        val (nextState, snapshot) = MarketSentimentCalculator.calculate(
            state = currentState,
            todayBarsBySymbol = partialTodayBars,
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
        )

        // sampleSize 应该只包含实际参与的股票
        assertEquals(25, snapshot.sampleSize, "sampleSize should only count participating stocks")
        // 返回 state 的 sampleCodes 应该包含全部 30 只
        assertEquals(symbolCount, nextState.sampleCodes.size, "sampleCodes should include all stocks")
        assertEquals(symbolCount, nextState.symbolStates.size, "symbolStates should include all stocks")
        // 缺失的 5 只股票的 state 应与前一日完全一致（未被推进）
        val prevStateByCode = currentState.symbolStates.associateBy { it.tsCode }
        val nextStateByCode = nextState.symbolStates.associateBy { it.tsCode }
        for (tsCode in missingCodes) {
            assertSymbolStateEquals(prevStateByCode.getValue(tsCode), nextStateByCode.getValue(tsCode))
        }
    }

    @Test
    @DisplayName("calculateStrict 对缺失 close 做 ffill 而非报错")
    fun testCalculateStrictFfillsMissingCloses() {
        val symbolCount = 20
        val totalDays = 150
        val splitAt = 100
        val requiredHistory = 10
        val barsBySymbol = createMultiSymbolBars(symbolCount, totalDays)
        val headBarsBySymbol = barsBySymbol.mapValues { it.value.take(splitAt) }
        val currentState = MarketSentimentCalculator.process(
            barsBySymbol = headBarsBySymbol,
            requiredHistory = requiredHistory,
        ).first

        val tradeDate = startDate.plus(DatePeriod(days = splitAt))
        val allCloses = barsBySymbol.mapValues { it.value[splitAt].close }
        // 只提供前 15 只股票的 close
        val partialCloses = allCloses.entries.sortedBy { it.key }.take(15)
            .associate { it.key to it.value }

        val (nextState, snapshot) = MarketSentimentCalculator.calculateStrict(
            state = currentState,
            observedClosesBySymbol = partialCloses,
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
        )

        // 全部 20 只股票都参与（缺失的做了 ffill）
        assertEquals(symbolCount, snapshot.sampleSize, "sampleSize should include all stocks (ffilled)")
        assertEquals(symbolCount, nextState.sampleCodes.size, "sampleCodes should include all stocks")
        // 缺失的 5 只股票的 prevClose 应该不变（step(prevClose) 产生 return=0）
        val prevStateByCode = currentState.symbolStates.associateBy { it.tsCode }
        val nextStateByCode = nextState.symbolStates.associateBy { it.tsCode }
        val missingCodes = allCloses.keys.sorted().drop(15)
        for (tsCode in missingCodes) {
            val prev = prevStateByCode.getValue(tsCode)
            val next = nextStateByCode.getValue(tsCode)
            assertDoubleEquals(prev.prevClose, next.prevClose, "$tsCode.prevClose after ffill", 1e-9)
        }
    }

    private fun assertSnapshotEquals(expected: MarketSentimentSnapshot, actual: MarketSentimentSnapshot) {
        assertEquals(expected.tradeDate, actual.tradeDate, "tradeDate")
        assertEquals(expected.signalBasis, actual.signalBasis, "signalBasis")
        assertEquals(expected.sampleSize, actual.sampleSize, "sampleSize")
        assertDoubleEquals(expected.bullRatio, actual.bullRatio, "bullRatio", 1e-9)
        assertDoubleEquals(expected.fftScore, actual.fftScore, "fftScore", 1e-9)
        assertDoubleEquals(expected.residualScore, actual.residualScore, "residualScore", 1e-6)
        assertDoubleEquals(expected.marketVol, actual.marketVol, "marketVol", 1e-9)
        assertDoubleEquals(expected.volZ, actual.volZ, "volZ", 1e-9)
        assertDoubleEquals(expected.accelZ, actual.accelZ, "accelZ", 1e-9)
        assertDoubleEquals(expected.sentimentExposure, actual.sentimentExposure, "sentimentExposure", 1e-9)
        assertDoubleEquals(expected.ratioNorm, actual.ratioNorm, "ratioNorm", 1e-9)
        assertDoubleEquals(expected.volScore, actual.volScore, "volScore", 1e-9)
        assertDoubleEquals(expected.accelScore, actual.accelScore, "accelScore", 1e-9)
        assertDoubleEquals(expected.absoluteFloor, actual.absoluteFloor, "absoluteFloor", 1e-9)
        assertDoubleEquals(expected.volCap, actual.volCap, "volCap", 1e-9)
        assertEquals(expected.sufficientHistory, actual.sufficientHistory, "sufficientHistory")
    }

    private fun assertSymbolStateEquals(expected: SymbolSentimentState, actual: SymbolSentimentState) {
        assertEquals(expected.tsCode, actual.tsCode, "tsCode")
        assertDoubleEquals(expected.emaShort, actual.emaShort, "emaShort", 1e-9)
        assertDoubleEquals(expected.emaLong, actual.emaLong, "emaLong", 1e-9)
        assertDoubleEquals(expected.prevClose, actual.prevClose, "prevClose", 1e-9)
        assertEquals(expected.returnWindowSize, actual.returnWindowSize, "returnWindowSize")
        assertDoubleEquals(expected.standardDeviation(), actual.standardDeviation(), "standardDeviation", 1e-9)
    }

    private fun assertDoubleEquals(expected: Double, actual: Double, field: String, tolerance: Double) {
        if (abs(expected - actual) > tolerance) {
            throw AssertionError("$field mismatch: expected=$expected, actual=$actual, diff=${abs(expected - actual)}")
        }
    }

    private val startDate = LocalDate(2023, 1, 1)

    private fun createMultiSymbolBars(symbolCount: Int, dayCount: Int): Map<String, List<PreparedBar>> {
        return (1..symbolCount).associate { idx ->
            val tsCode = String.format("%06d.SZ", idx)
            tsCode to createBars(tsCode, dayCount, baseClose = 10.0 + idx * 0.5)
        }
    }

    private fun createBars(tsCode: String, count: Int, baseClose: Double = 10.0): List<PreparedBar> {
        return (0 until count).map { dayIdx ->
            // 每只股票的 close 带一点独立偏移和周期性波动，确保 EMA / return 有代表性
            val close = baseClose + dayIdx * 0.02 + kotlin.math.sin(dayIdx * 0.1) * 0.3
            val open = close * (0.995 + (dayIdx % 7) * 0.001)
            val high = close * 1.01
            val low = close * 0.99
            val volume = 10000.0 + dayIdx * 20
            PreparedBar(
                tsCode = tsCode,
                date = startDate.plus(DatePeriod(days = dayIdx)),
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

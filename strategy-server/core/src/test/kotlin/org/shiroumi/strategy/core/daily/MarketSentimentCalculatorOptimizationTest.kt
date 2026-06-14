package org.shiroumi.strategy.core.daily

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
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * R2-OPT-3-sentiment-prefix 优化验收测试。
 *
 * 验证两处滑窗前缀和/前缀计数优化与原朴素 O(n*window) 实现逐元素等价：
 *  - [zScoreSeries]：前缀和 + 前缀平方和，O(n) z-score。
 *  - [applyOverheatGuardInPlace]：滚动前缀计数，O(n) overheat 衰减。
 *
 * 私有顶层函数通过文件 facade 类 `MarketSentimentCalculatorKt` 反射调用，
 * 生产代码可见性不被放宽。朴素基线在本测试内重新实现作为 oracle。
 */
class MarketSentimentCalculatorOptimizationTest {

    private val facade: Class<*> = Class.forName(
        "org.shiroumi.strategy.core.daily.MarketSentimentCalculatorKt"
    )

    private val zScoreSeriesMethod: Method = facade
        .getDeclaredMethod(
            "zScoreSeries",
            DoubleArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }

    private val overheatMethod: Method = facade
        .getDeclaredMethod(
            "applyOverheatGuardInPlace",
            DoubleArray::class.java,
            Double::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }

    private fun zScoreOptimized(values: DoubleArray, window: Int, minPeriods: Int): DoubleArray =
        zScoreSeriesMethod.invoke(null, values, window, minPeriods) as DoubleArray

    /** 原始 O(n*window) 实现的逐窗口忠实复刻，作为正确性 oracle。 */
    private fun zScoreNaive(values: DoubleArray, window: Int, minPeriods: Int): DoubleArray {
        val out = DoubleArray(values.size)
        for (index in values.indices) {
            val from = max(0, index - window + 1)
            val size = index - from + 1
            if (size < minPeriods) {
                out[index] = 0.0
                continue
            }
            var sum = 0.0
            for (i in from..index) sum += values[i]
            val mean = sum / size
            var variance = 0.0
            for (i in from..index) {
                val diff = values[i] - mean
                variance += diff * diff
            }
            val std = sqrt(variance / size).takeIf { it > 1e-8 } ?: 1.0
            out[index] = (values[index] - mean) / std
        }
        return out
    }

    private fun overheatOptimized(
        values: DoubleArray,
        threshold: Double,
        days: Int,
        decay: Double,
    ): DoubleArray {
        val copy = values.copyOf()
        overheatMethod.invoke(null, copy, threshold, days, decay)
        return copy
    }

    /** 原始 O(n*days) in-place 实现的忠实复刻：左侧读取已衰减值，自身读原值。 */
    private fun overheatNaive(
        values: DoubleArray,
        threshold: Double,
        days: Int,
        decay: Double,
    ): DoubleArray {
        val series = values.copyOf()
        if (series.isEmpty()) return series
        for (i in series.indices) {
            val from = max(0, i - days + 1)
            var count = 0
            for (j in from..i) {
                if (series[j] > threshold) count += 1
            }
            val ratio = count.toDouble() / (i - from + 1).toDouble().coerceAtLeast(1.0)
            series[i] = series[i] * decay.pow(ratio)
        }
        return series
    }

    private fun assertArraysClose(expected: DoubleArray, actual: DoubleArray, tol: Double, label: String) {
        assertEquals(expected.size, actual.size, "$label: size mismatch")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertTrue(e.isFinite(), "$label: expected[$i] not finite = $e")
            assertTrue(a.isFinite(), "$label: actual[$i] not finite = $a")
            assertTrue(
                abs(e - a) <= tol,
                "$label: index=$i expected=$e actual=$a diff=${abs(e - a)} > tol=$tol",
            )
        }
    }

    // ============================================================
    // 第一部分: zScoreSeries 前缀和实现验证
    // ============================================================

    @Test
    @DisplayName("Test1 zScore 随机数据逐元素对比 (naive vs prefix-sum)")
    fun test1_zScoreRandomEquivalence() {
        val rng = Random(42)
        val values = DoubleArray(1000) { rng.nextDouble(-50.0, 50.0) }
        val naive = zScoreNaive(values, window = 252, minPeriods = 60)
        val optimized = zScoreOptimized(values, window = 252, minPeriods = 60)
        assertArraysClose(naive, optimized, 1e-9, "zScore-random")
    }

    @Test
    @DisplayName("Test2 zScore minPeriods 边界: size<60 输出 0, size>=60 起计算")
    fun test2_zScoreMinPeriodsBoundary() {
        val values = DoubleArray(100) { it.toDouble() }
        val result = zScoreOptimized(values, window = 252, minPeriods = 60)
        // size = index + 1（window 远大于序列长度，from 恒为 0）。
        // size < 60 即 index < 59 输出 0.0；index >= 59 (size >= 60) 起真实计算。
        for (i in 0 until 59) {
            assertEquals(0.0, result[i], 0.0, "index=$i size=${i + 1}<60 must be exactly 0.0")
        }
        for (i in 59 until 100) {
            assertTrue(result[i].isFinite(), "index=$i must be finite")
            assertTrue(abs(result[i]) > 1e-12, "index=$i must be non-zero (递增序列窗口末位 z-score 非零)")
        }
        // 与 naive 完全一致
        assertArraysClose(zScoreNaive(values, 252, 60), result, 1e-9, "zScore-ramp")
    }

    @Test
    @DisplayName("Test3 zScore 单元素数组")
    fun test3_zScoreSingleElement() {
        val result = zScoreOptimized(doubleArrayOf(5.0), window = 252, minPeriods = 1)
        // size=1 -> variance=0 -> std fallback 1.0 -> (5-5)/1 = 0
        assertEquals(0.0, result[0], 1e-12)
    }

    @Test
    @DisplayName("Test4 zScore 全相同常数序列")
    fun test4_zScoreConstantSeries() {
        val values = DoubleArray(100) { 0.5 }
        val result = zScoreOptimized(values, window = 252, minPeriods = 60)
        for (i in 60 until 100) {
            assertEquals(0.0, result[i], 1e-12, "constant series index=$i must be 0.0")
        }
        assertArraysClose(zScoreNaive(values, 252, 60), result, 1e-9, "zScore-constant")
    }

    @Test
    @DisplayName("Test5 zScore 空数组")
    fun test5_zScoreEmpty() {
        val result = zScoreOptimized(doubleArrayOf(), window = 252, minPeriods = 60)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("Test6 zScore 小窗口 (window < length)")
    fun test6_zScoreSmallWindow() {
        val rng = Random(7)
        val values = DoubleArray(100) { rng.nextDouble(-5.0, 5.0) }
        val naive = zScoreNaive(values, window = 20, minPeriods = 15)
        val optimized = zScoreOptimized(values, window = 20, minPeriods = 15)
        assertArraysClose(naive, optimized, 1e-9, "zScore-smallwindow")
    }

    @Test
    @DisplayName("Test7 zScore 极端值混合精度压力 不产生 Inf/NaN")
    fun test7_zScoreExtremeMagnitudes() {
        val rng = Random(99)
        val values = DoubleArray(100) {
            if (rng.nextBoolean()) rng.nextDouble(1e-10, 5e-10) else rng.nextDouble(1e9, 5e9)
        }
        val optimized = zScoreOptimized(values, window = 252, minPeriods = 60)
        for (i in optimized.indices) {
            assertTrue(optimized[i].isFinite(), "index=$i produced non-finite ${optimized[i]}")
        }
    }

    @Test
    @DisplayName("Test7b zScore 真实尺度数据 (bull ratio [0,1] / accel [-0.1,0.1]) 严格 1e-9")
    fun test7b_zScoreRealisticScale() {
        val rng = Random(2024)
        val bullRatio = DoubleArray(400) { rng.nextDouble(0.0, 1.0) }
        val accel = DoubleArray(400) { rng.nextDouble(-0.1, 0.1) }
        assertArraysClose(
            zScoreNaive(bullRatio, 252, 60),
            zScoreOptimized(bullRatio, 252, 60),
            1e-9,
            "zScore-bullRatio",
        )
        assertArraysClose(
            zScoreNaive(accel, 252, 60),
            zScoreOptimized(accel, 252, 60),
            1e-9,
            "zScore-accel",
        )
    }

    // ============================================================
    // 第二部分: applyOverheatGuardInPlace 前缀计数验证
    // ============================================================

    @Test
    @DisplayName("Test8 overheat 随机数据逐元素对比 (naive vs prefix-count)")
    fun test8_overheatRandomEquivalence() {
        val rng = Random(123)
        val values = DoubleArray(100) { rng.nextDouble(0.0, 1.0) }
        val naive = overheatNaive(values, threshold = 0.5, days = 10, decay = 0.8)
        val optimized = overheatOptimized(values, threshold = 0.5, days = 10, decay = 0.8)
        assertArraysClose(naive, optimized, 1e-9, "overheat-random")
    }

    @Test
    @DisplayName("Test9 overheat 无值超过 threshold 值不变")
    fun test9_overheatNoExceed() {
        val input = doubleArrayOf(0.1, 0.2, 0.3, 0.1, 0.2)
        val result = overheatOptimized(input, threshold = 0.5, days = 5, decay = 0.8)
        // count=0 -> ratio=0 -> decay^0=1.0
        assertArraysClose(input, result, 0.0, "overheat-noexceed")
    }

    @Test
    @DisplayName("Test10 overheat 全部超过 threshold 最大衰减")
    fun test10_overheatAllExceed() {
        val input = doubleArrayOf(0.9, 0.8, 0.7, 0.6)
        val result = overheatOptimized(input, threshold = 0.4, days = 4, decay = 0.5)
        // 窗口内全部 > threshold (含已衰减后仍 > 0.4) -> ratio=1.0 -> 乘 0.5
        // 逐元素验证：每个值的窗口内左侧已衰减但仍 > 0.4，count = 窗口大小
        val naive = overheatNaive(input, threshold = 0.4, days = 4, decay = 0.5)
        assertArraysClose(naive, result, 1e-12, "overheat-allexceed")
        // index0: 0.9*0.5=0.45; index1 窗口[0,1]=[0.45,0.8] 都>0.4 count2 ratio1 -> 0.8*0.5=0.4
        assertEquals(0.45, result[0], 1e-12)
    }

    @Test
    @DisplayName("Test11 overheat 部分超过 threshold 混合场景")
    fun test11_overheatPartialExceed() {
        val input = doubleArrayOf(0.3, 0.7, 0.2, 0.8, 0.5)
        val naive = overheatNaive(input, threshold = 0.5, days = 5, decay = 0.8)
        val optimized = overheatOptimized(input, threshold = 0.5, days = 5, decay = 0.8)
        assertArraysClose(naive, optimized, 1e-9, "overheat-partial")
    }

    @Test
    @DisplayName("Test12 overheat 空数组")
    fun test12_overheatEmpty() {
        val result = overheatOptimized(doubleArrayOf(), threshold = 0.5, days = 10, decay = 0.8)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("Test13 overheat 单元素")
    fun test13_overheatSingle() {
        val result = overheatOptimized(doubleArrayOf(0.6), threshold = 0.5, days = 10, decay = 0.8)
        // count=1, ratio=1.0, 0.6 * 0.8 = 0.48
        assertEquals(0.6 * 0.8, result[0], 1e-12)
    }

    @Test
    @DisplayName("Test14 overheat 局部窗口边界 days 限制")
    fun test14_overheatWindowBoundary() {
        val input = DoubleArray(20) { if (it < 10) 0.9 else 0.1 }
        val naive = overheatNaive(input, threshold = 0.5, days = 5, decay = 0.8)
        val optimized = overheatOptimized(input, threshold = 0.5, days = 5, decay = 0.8)
        assertArraysClose(naive, optimized, 1e-12, "overheat-windowboundary")
    }

    @Test
    @DisplayName("Test14b overheat 衰减导致左侧跨阈值 (前缀计数必须沿已定型值滚动)")
    fun test14b_overheatDecayCrossesThreshold() {
        // 构造左侧值刚好略高于 threshold，一次衰减后落到 threshold 之下，
        // 验证优化实现没有用原始数组建静态前缀计数（那样会算错 count）。
        val input = doubleArrayOf(0.51, 0.51, 0.51, 0.51, 0.51, 0.51)
        // decay=0.5 衰减一次后 0.51*0.5≈0.255 < 0.5，左侧不再计数
        val naive = overheatNaive(input, threshold = 0.5, days = 6, decay = 0.5)
        val optimized = overheatOptimized(input, threshold = 0.5, days = 6, decay = 0.5)
        assertArraysClose(naive, optimized, 1e-12, "overheat-decaycross")
        // 朴素逐元素：i0 self>0.5 count1 ratio1 ->0.255; i1 左侧0.255<0.5 self0.51>0.5 count1 ratio0.5 ...
        assertEquals(0.51 * 0.5.pow(1.0), optimized[0], 1e-12)
        // i1: 窗口[0,1]=[0.255,0.51], left count0 + self1 =1, size2, ratio0.5 -> 0.51*0.5^0.5
        assertEquals(0.51 * 0.5.pow(0.5), optimized[1], 1e-12)
    }

    @Test
    @DisplayName("Test14c overheat 长随机序列 days=10 严格对比")
    fun test14c_overheatLongRandom() {
        val rng = Random(555)
        val values = DoubleArray(500) { rng.nextDouble(0.0, 1.0) }
        val naive = overheatNaive(values, threshold = 0.45, days = 10, decay = 0.9)
        val optimized = overheatOptimized(values, threshold = 0.45, days = 10, decay = 0.9)
        assertArraysClose(naive, optimized, 1e-9, "overheat-longrandom")
    }

    // ============================================================
    // 第三部分: 集成验证 (公开 API 端到端一致)
    // ============================================================

    private fun createBars(tsCode: String, slope: Double, count: Int): List<PreparedBar> {
        val start = LocalDate(2023, 1, 1)
        return (0 until count).map { index ->
            val price = 10.0 + index * 0.03 * slope + sin(index / 9.0) * 0.4
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

    @Test
    @DisplayName("Test15 process() 端到端: zScore+overheat 优化下快照仍有效且各分量落界")
    fun test15_processSnapshotValid() {
        val barsBySymbol = mapOf(
            "600000.SH" to createBars("600000.SH", 1.0, 400),
            "000001.SZ" to createBars("000001.SZ", 0.8, 400),
            "601318.SH" to createBars("601318.SH", 1.2, 400),
        )
        val (_, snapshot) = MarketSentimentCalculator.process(
            barsBySymbol = barsBySymbol,
            requiredHistory = 300,
            volDynBoost = 0.4,
            sentOverheatThresh = 0.7,
            sentOverheatDays = 10,
            sentOverheatDecay = 0.9,
        )
        assertTrue(snapshot.sufficientHistory)
        assertTrue(snapshot.sentimentExposure in 0.0..1.0, "exposure=${snapshot.sentimentExposure}")
        assertTrue(snapshot.bullRatio in 0.0..1.0, "bullRatio=${snapshot.bullRatio}")
        assertTrue(snapshot.fftScore in 0.0..1.0, "fftScore=${snapshot.fftScore}")
        assertTrue(snapshot.residualScore in 0.0..1.0, "residualScore=${snapshot.residualScore}")
        assertTrue(snapshot.volZ.isFinite(), "volZ=${snapshot.volZ}")
        assertTrue(snapshot.accelZ.isFinite(), "accelZ=${snapshot.accelZ}")
    }

    @Test
    @DisplayName("Test16 增量 calculate 与重算 process 在同一序列上 z-score/exposure 一致")
    fun test16_incrementalMatchesBatch() {
        // 用 399 天 process 建状态，再增量推 1 天；与对 400 天直接 process 的结果对齐。
        val full = mapOf(
            "600000.SH" to createBars("600000.SH", 1.0, 400),
            "000001.SZ" to createBars("000001.SZ", 0.8, 400),
            "601318.SH" to createBars("601318.SH", 1.2, 400),
        )
        val head = full.mapValues { (_, bars) -> bars.dropLast(1) }
        val lastDay = full.mapValues { (_, bars) -> bars.last() }
        val tradeDate = full.values.first().last().date

        val (state, _) = MarketSentimentCalculator.process(
            barsBySymbol = head,
            requiredHistory = 300,
            sentOverheatThresh = 0.7,
            sentOverheatDays = 10,
            sentOverheatDecay = 0.9,
        )
        val (_, incSnap) = MarketSentimentCalculator.calculate(
            state = state,
            todayBarsBySymbol = lastDay,
            tradeDate = tradeDate,
            requiredHistory = 300,
            sentOverheatThresh = 0.7,
            sentOverheatDays = 10,
            sentOverheatDecay = 0.9,
        )
        val (_, batchSnap) = MarketSentimentCalculator.process(
            barsBySymbol = full,
            requiredHistory = 300,
            sentOverheatThresh = 0.7,
            sentOverheatDays = 10,
            sentOverheatDecay = 0.9,
        )
        // 增量 z-score（zScoreOfLast）走另一条不变的码路，与批量 zScoreSeries 末位应高度一致。
        assertEquals(batchSnap.volZ, incSnap.volZ, 1e-9, "volZ batch vs incremental")
        assertEquals(batchSnap.accelZ, incSnap.accelZ, 1e-9, "accelZ batch vs incremental")
        assertEquals(batchSnap.bullRatio, incSnap.bullRatio, 1e-9, "bullRatio batch vs incremental")
    }

    @Test
    @DisplayName("Test17 历史不足时返回 sufficientHistory=false 且原因非空")
    fun test17_insufficientHistory() {
        val barsBySymbol = mapOf(
            "600000.SH" to createBars("600000.SH", 1.0, 120),
            "000001.SZ" to createBars("000001.SZ", 0.8, 120),
        )
        val (_, snapshot) = MarketSentimentCalculator.process(
            barsBySymbol = barsBySymbol,
            requiredHistory = 300,
        )
        assertFalse(snapshot.sufficientHistory)
        assertTrue(snapshot.reason != null)
        assertEquals(0.0, snapshot.sentimentExposure, 1e-9)
    }
}

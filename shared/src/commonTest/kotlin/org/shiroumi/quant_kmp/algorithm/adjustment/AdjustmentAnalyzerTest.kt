package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 复权分析器单元测试
 */
class AdjustmentAnalyzerTest {

    private companion object {
        const val DELTA = 1e-4
    }

    private fun createTestCandles(): List<OhlcvCandle> {
        return listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0, low = 101.0,
                close = 106.0, volume = 12000.0, amount = 1272000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 3),
                open = 106.0, high = 110.0, low = 104.0,
                close = 108.0, volume = 15000.0, amount = 1620000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 4),
                open = 108.0, high = 112.0, low = 107.0,
                close = 110.0, volume = 11000.0, amount = 1210000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 5),
                open = 110.0, high = 115.0, low = 109.0,
                close = 114.0, volume = 13000.0, amount = 1482000.0
            )
        )
    }

    @Test
    fun `test analyze impact with empty list`() {
        val impact = AdjustmentAnalyzer.analyzeImpact(emptyList())

        assertEquals(0.0, impact.averageImpact, DELTA)
        assertEquals(0.0, impact.maxImpact, DELTA)
        assertEquals(0.0, impact.minImpact, DELTA)
        assertTrue(impact.yearlyImpact.isEmpty())
    }

    @Test
    fun `test analyze impact with data`() {
        val comparison = listOf(
            PriceComparison(
                date = LocalDate(2024, 1, 1),
                originalClose = 100.0, adjustedClose = 98.0,
                changeRatio = -0.02,
                originalVolume = 10000.0, adjustedVolume = 10204.0
            ),
            PriceComparison(
                date = LocalDate(2024, 1, 2),
                originalClose = 105.0, adjustedClose = 102.9,
                changeRatio = -0.02,
                originalVolume = 12000.0, adjustedVolume = 12245.0
            ),
            PriceComparison(
                date = LocalDate(2024, 1, 3),
                originalClose = 110.0, adjustedClose = 107.8,
                changeRatio = -0.02,
                originalVolume = 15000.0, adjustedVolume = 15306.0
            )
        )

        val impact = AdjustmentAnalyzer.analyzeImpact(comparison)

        assertEquals(-0.02, impact.averageImpact, DELTA)
        assertEquals(-0.02, impact.maxImpact, DELTA)
        assertEquals(-0.02, impact.minImpact, DELTA)
        assertEquals(1, impact.yearlyImpact.size)
        assertEquals(2024, impact.yearlyImpact.keys.first())
        assertEquals(-0.02, impact.yearlyImpact[2024] ?: 0.0, DELTA)
    }

    @Test
    fun `test analyze impact with multiple years`() {
        val comparison = listOf(
            PriceComparison(
                date = LocalDate(2023, 6, 1),
                originalClose = 100.0, adjustedClose = 95.0,
                changeRatio = -0.05,
                originalVolume = 10000.0, adjustedVolume = 10526.0
            ),
            PriceComparison(
                date = LocalDate(2024, 6, 1),
                originalClose = 110.0, adjustedClose = 105.6,
                changeRatio = -0.04,
                originalVolume = 12000.0, adjustedVolume = 12500.0
            )
        )

        val impact = AdjustmentAnalyzer.analyzeImpact(comparison)

        assertEquals(2, impact.yearlyImpact.size)
        assertTrue(impact.yearlyImpact.containsKey(2023))
        assertTrue(impact.yearlyImpact.containsKey(2024))
    }

    @Test
    fun `test detect anomalies with normal data`() {
        val candles = createTestCandles()
        val anomalies = AdjustmentAnalyzer.detectAnomalies(candles, threshold = 0.2)

        // 正常数据不应该有异常
        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `test detect anomalies with price jump`() {
        val candles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0, low = 101.0,
                close = 150.0, // 价格跳涨近50%
                volume = 12000.0, amount = 1800000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 3),
                open = 150.0, high = 155.0, low = 148.0,
                close = 152.0, volume = 50000.0, // 成交量异常
                amount = 7600000.0
            )
        )

        val anomalies = AdjustmentAnalyzer.detectAnomalies(candles, threshold = 0.2)

        assertEquals(2, anomalies.size)

        // 第一个异常：价格跳涨
        assertTrue(anomalies[0].isPriceAnomaly)
        assertFalse(anomalies[0].isVolumeAnomaly)

        // 第二个异常：价格和成交量都异常
        assertTrue(anomalies[1].isPriceAnomaly || anomalies[1].isVolumeAnomaly)
    }

    @Test
    fun `test detect anomalies with single candle`() {
        val singleCandle = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            )
        )

        val anomalies = AdjustmentAnalyzer.detectAnomalies(singleCandle)
        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `test detect anomalies with empty list`() {
        val anomalies = AdjustmentAnalyzer.detectAnomalies(emptyList())
        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `test generate report`() {
        val original = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }
        val result = original.forwardAdjust(events, 114.0)

        val report = AdjustmentAnalyzer.generateReport(result, original)

        assertEquals(AdjustmentType.FORWARD, report.adjustmentType)
        assertEquals(5, report.totalCandles)
        assertEquals(1, report.adjustmentEvents)
        assertFalse(report.hasAnomalies)
        assertEquals(0, report.anomalyCount)
        assertEquals(5, report.priceComparisons.size)

        // 验证影响分析
        assertNotNull(report.impactAnalysis)
        assertTrue(report.impactAnalysis.maxImpact <= 0) // 前复权应该是负影响
    }

    @Test
    fun `test generate report with anomalies`() {
        val original = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0, low = 101.0,
                close = 160.0, // 异常价格跳涨
                volume = 12000.0, amount = 1920000.0
            )
        )

        val events = adjustmentEvents {
            stockSplit(LocalDate(2024, 1, 2), 0.5, "送股")
        }
        val result = original.forwardAdjust(events, 160.0)

        val report = AdjustmentAnalyzer.generateReport(result, original)

        assertTrue(report.totalCandles > 0)
        assertNotNull(report.impactAnalysis)
    }

    @Test
    fun `test impact analysis with positive and negative changes`() {
        val comparison = listOf(
            PriceComparison(
                date = LocalDate(2024, 1, 1),
                originalClose = 100.0, adjustedClose = 98.0,
                changeRatio = -0.02,
                originalVolume = 10000.0, adjustedVolume = 10204.0
            ),
            PriceComparison(
                date = LocalDate(2024, 1, 2),
                originalClose = 100.0, adjustedClose = 102.0,
                changeRatio = 0.02,
                originalVolume = 12000.0, adjustedVolume = 11765.0
            ),
            PriceComparison(
                date = LocalDate(2024, 1, 3),
                originalClose = 100.0, adjustedClose = 96.0,
                changeRatio = -0.04,
                originalVolume = 15000.0, adjustedVolume = 15625.0
            )
        )

        val impact = AdjustmentAnalyzer.analyzeImpact(comparison)

        // 平均影响应该是 (-0.02 + 0.02 - 0.04) / 3 = -0.0133
        assertEquals(-0.0133, impact.averageImpact, 0.001)

        // 最大影响是 0.02
        assertEquals(0.02, impact.maxImpact, DELTA)

        // 最小影响是 -0.04
        assertEquals(-0.04, impact.minImpact, DELTA)
    }

    @Test
    fun `test anomaly detection with volume only`() {
        val candles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 103.0, high = 107.0, low = 101.0,
                close = 105.0, // 正常价格变化
                volume = 50000.0, // 但成交量异常（5倍）
                amount = 5250000.0
            )
        )

        val anomalies = AdjustmentAnalyzer.detectAnomalies(candles, threshold = 0.2)

        assertEquals(1, anomalies.size)
        assertFalse(anomalies[0].isPriceAnomaly)
        assertTrue(anomalies[0].isVolumeAnomaly)
    }

    @Test
    fun `test anomaly detection with zero volume`() {
        val candles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 0.0, amount = 0.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 103.0, high = 107.0, low = 101.0,
                close = 105.0, volume = 10000.0, amount = 1050000.0
            )
        )

        val anomalies = AdjustmentAnalyzer.detectAnomalies(candles, threshold = 0.2)

        // 从0到10000，变化是无穷大，应该被检测为异常
        assertTrue(anomalies.isNotEmpty())
    }

    @Test
    fun `test report with multiple adjustment events`() {
        val original = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 2), 1.0, "第一次分红")
            stockSplit(LocalDate(2024, 1, 4), 0.5, "送股")
        }
        val result = original.forwardAdjust(events, 114.0)

        val report = AdjustmentAnalyzer.generateReport(result, original)

        assertEquals(2, report.adjustmentEvents)
        assertEquals(5, report.priceComparisons.size)

        // 验证价格对比数据
        report.priceComparisons.forEach { comp ->
            assertNotNull(comp.date)
            assertTrue(comp.originalClose > 0)
            assertTrue(comp.adjustedClose > 0)
        }
    }

    @Test
    fun `test yearly impact calculation`() {
        val comparison = listOf(
            PriceComparison(
                date = LocalDate(2023, 1, 1),
                originalClose = 100.0, adjustedClose = 95.0,
                changeRatio = -0.05,
                originalVolume = 10000.0, adjustedVolume = 10526.0
            ),
            PriceComparison(
                date = LocalDate(2023, 6, 1),
                originalClose = 110.0, adjustedClose = 104.5,
                changeRatio = -0.05,
                originalVolume = 12000.0, adjustedVolume = 12632.0
            ),
            PriceComparison(
                date = LocalDate(2024, 1, 1),
                originalClose = 120.0, adjustedClose = 114.0,
                changeRatio = -0.05,
                originalVolume = 15000.0, adjustedVolume = 15789.0
            ),
            PriceComparison(
                date = LocalDate(2024, 6, 1),
                originalClose = 130.0, adjustedClose = 123.5,
                changeRatio = -0.05,
                originalVolume = 11000.0, adjustedVolume = 11579.0
            )
        )

        val impact = AdjustmentAnalyzer.analyzeImpact(comparison)

        assertEquals(2, impact.yearlyImpact.size)
        assertEquals(-0.05, impact.yearlyImpact[2023] ?: 0.0, DELTA)
        assertEquals(-0.05, impact.yearlyImpact[2024] ?: 0.0, DELTA)
    }
}

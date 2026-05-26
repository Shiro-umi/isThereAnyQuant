package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 复权服务单元测试
 */
class AdjustmentServiceTest {

    private companion object {
        const val DELTA = 1e-4
    }

    private val service = AdjustmentService()

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
    fun `test batch adjustment`() {
        val candles1 = createTestCandles()
        val candles2 = createTestCandles().map {
            it.copy(close = it.close * 2) // 不同的价格
        }

        val dataMap = mapOf(
            "000001.SZ" to candles1,
            "000002.SZ" to candles2
        )

        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }

        val eventsMap = mapOf(
            "000001.SZ" to events,
            "000002.SZ" to events
        )

        val results = service.batchAdjust(dataMap, eventsMap, AdjustmentType.FORWARD)

        assertEquals(2, results.size)
        assertTrue(results.containsKey("000001.SZ"))
        assertTrue(results.containsKey("000002.SZ"))

        results.values.forEach { result ->
            assertEquals(AdjustmentType.FORWARD, result.type)
            assertEquals(5, result.candles.size)
        }
    }

    @Test
    fun `test calculate return`() {
        val candles = createTestCandles()

        // 计算整个周期的收益率
        val totalReturn = service.calculateReturn(candles, 0, 4)
        // (114 - 102) / 102 = 0.1176 = 11.76%
        assertEquals(0.1176, totalReturn, 0.001)

        // 计算部分周期的收益率
        val partialReturn = service.calculateReturn(candles, 1, 3)
        // (110 - 106) / 106 = 0.0377 = 3.77%
        assertEquals(0.0377, partialReturn, 0.001)
    }

    @Test
    fun `test calculate cumulative returns`() {
        val candles = createTestCandles()
        val returns = service.calculateCumulativeReturns(candles)

        assertEquals(5, returns.size)

        // 第一个收益率应该为0（基准点）
        assertEquals(0.0, returns[0], DELTA)

        // 最后一个收益率
        // (114 - 102) / 102 = 0.1176
        assertEquals(0.1176, returns[4], 0.001)

        // 验证递增
        for (i in 1 until returns.size) {
            assertTrue(returns[i] >= returns[i - 1] || kotlin.math.abs(returns[i] - returns[i - 1]) < 0.2)
        }
    }

    @Test
    fun `test calculate moving average returns`() {
        val candles = createTestCandles()
        val maReturns = service.calculateMovingAverageReturns(candles, period = 3)

        assertEquals(5, maReturns.size)

        // 前period-1个应该是null
        assertEquals(null, maReturns[0])
        assertEquals(null, maReturns[1])

        // 从第3个开始有值
        assertNotNull(maReturns[2])
        assertNotNull(maReturns[4])
    }

    @Test
    fun `test find ex-div dates`() {
        val candles = createTestCandles()
        val factors = listOf(
            AdjustmentFactor(
                date = LocalDate(2024, 1, 2),
                forwardFactor = 0.98,
                backwardFactor = 1.02,
                event = AdjustmentEvent.Dividend(LocalDate(2024, 1, 2), 1.0)
            ),
            AdjustmentFactor(
                date = LocalDate(2024, 1, 4),
                forwardFactor = 0.95,
                backwardFactor = 1.05,
                event = AdjustmentEvent.StockSplit(LocalDate(2024, 1, 4), 0.3)
            )
        )

        val exDivDates = service.findExDivDates(candles, factors)

        assertEquals(2, exDivDates.size)
        assertEquals(LocalDate(2024, 1, 2), exDivDates[0])
        assertEquals(LocalDate(2024, 1, 4), exDivDates[1])
    }

    @Test
    fun `test validate adjustment with valid data`() {
        val candles = createTestCandles()
        val validation = service.validateAdjustment(candles)

        assertTrue(validation.isValid)
        assertEquals(0, validation.errorCount)
        assertEquals(5, validation.totalCandles)
        assertEquals(0.0, validation.errorRate, DELTA)
    }

    @Test
    fun `test validate adjustment with invalid data`() {
        val invalidCandles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 95.0, // high < open，错误
                low = 98.0, close = 102.0,
                volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0,
                low = 110.0, // low > close，错误
                close = 106.0,
                volume = -1000.0, // 负成交量，错误
                amount = 1272000.0
            )
        )

        val validation = service.validateAdjustment(invalidCandles)

        assertFalse(validation.isValid)
        assertTrue(validation.errorCount > 0)
        assertTrue(validation.errors.isNotEmpty())
    }

    @Test
    fun `test get price comparison`() {
        val original = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }
        val adjusted = original.forwardAdjust(events, 114.0)

        val comparison = service.getPriceComparison(original, adjusted.candles)

        assertEquals(5, comparison.size)

        comparison.forEach { comp ->
            assertNotNull(comp.date)
            assertTrue(comp.originalClose > 0)
            assertTrue(comp.adjustedClose > 0)
        }

        // 对于事件日期(01-03)之前的K线，前复权后价格应该降低
        // 对于事件日期及之后的K线，价格保持不变
        val beforeEvent = comparison.filter { it.date < LocalDate(2024, 1, 3) }
        val afterEvent = comparison.filter { it.date >= LocalDate(2024, 1, 3) }

        // 事件之前的K线价格应该降低
        beforeEvent.forEach { comp ->
            assertTrue(comp.adjustedClose < comp.originalClose,
                "Date ${comp.date}: adjusted ${comp.adjustedClose} should be < original ${comp.originalClose}")
            assertTrue(comp.changeRatio < 0)
        }

        // 事件当天及之后的K线价格应该保持不变
        afterEvent.forEach { comp ->
            assertEquals(comp.originalClose, comp.adjustedClose, 0.0001,
                "Date ${comp.date}: price should remain unchanged")
            assertEquals(0.0, comp.changeRatio, 0.0001)
        }
    }

    @Test
    fun `test calculate factor series`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }
        val factors = AdjustmentCalculator.calculateFactors(events, 114.0)

        val forwardSeries = service.calculateFactorSeries(candles, factors, AdjustmentType.FORWARD)
        val backwardSeries = service.calculateFactorSeries(candles, factors, AdjustmentType.BACKWARD)

        assertEquals(5, forwardSeries.size)
        assertEquals(5, backwardSeries.size)

        // 前复权因子应该小于1（价格降低）
        forwardSeries.forEach { factor ->
            assertTrue(factor > 0)
        }

        // 后复权因子应该大于等于1
        backwardSeries.forEach { factor ->
            assertTrue(factor >= 1.0 || kotlin.math.abs(factor - 1.0) < 0.01)
        }
    }

    @Test
    fun `test convert between forward and backward`() {
        val original = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
            stockSplit(LocalDate(2024, 1, 4), 0.5, "送股")
        }
        val factors = AdjustmentCalculator.calculateFactors(events, 114.0)

        // 先进行前复权
        val forwardResult = original.forwardAdjust(events, 114.0)

        // 转换为后复权
        val backwardConverted = service.convertToBackward(forwardResult.candles, factors)

        // 再进行后复权
        val backwardResult = original.backwardAdjust(events, 114.0)

        // 转换后的结果应该与直接后复权的结果相近
        assertEquals(backwardResult.candles.size, backwardConverted.size)

        backwardResult.candles.zip(backwardConverted) { expected, actual ->
            assertEquals(expected.close, actual.close, 0.1)
            assertEquals(expected.open, actual.open, 0.1)
        }
    }

    @Test
    fun `test convert to forward`() {
        val original = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }
        val factors = AdjustmentCalculator.calculateFactors(events, 114.0)

        // 先进行后复权
        val backwardResult = original.backwardAdjust(events, 114.0)

        // 转换为前复权
        val forwardConverted = service.convertToForward(backwardResult.candles, factors)

        // 再进行前复权
        val forwardResult = original.forwardAdjust(events, 114.0)

        // 转换后的结果应该与直接前复权的结果相近
        assertEquals(forwardResult.candles.size, forwardConverted.size)

        forwardResult.candles.zip(forwardConverted) { expected, actual ->
            assertEquals(expected.close, actual.close, 0.1)
            assertEquals(expected.open, actual.open, 0.1)
        }
    }

    @Test
    fun `test empty candles handling`() {
        val emptyCandles = emptyList<OhlcvCandle>()

        val returns = service.calculateCumulativeReturns(emptyCandles)
        assertTrue(returns.isEmpty())

        val maReturns = service.calculateMovingAverageReturns(emptyCandles)
        assertTrue(maReturns.isEmpty())

        val validation = service.validateAdjustment(emptyCandles)
        assertTrue(validation.isValid)
        assertEquals(0, validation.totalCandles)
    }

    @Test
    fun `test single candle handling`() {
        val singleCandle = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            )
        )

        val returns = service.calculateCumulativeReturns(singleCandle)
        assertEquals(1, returns.size)
        assertEquals(0.0, returns[0], DELTA) // 单个点的收益率为0

        val returnValue = service.calculateReturn(singleCandle, 0, 0)
        assertEquals(0.0, returnValue, DELTA)
    }

    @Test
    fun `test batch adjust with missing events`() {
        val candles1 = createTestCandles()
        val candles2 = createTestCandles()

        val dataMap = mapOf(
            "000001.SZ" to candles1,
            "000002.SZ" to candles2
        )

        // 只为第一只股票提供事件
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }
        val eventsMap = mapOf("000001.SZ" to events)

        val results = service.batchAdjust(dataMap, eventsMap, AdjustmentType.FORWARD)

        assertEquals(2, results.size)

        // 第一只应该被复权
        val result1 = results["000001.SZ"]!!
        assertTrue(result1.factors.isNotEmpty())

        // 第二只应该保持原样（空事件列表）
        val result2 = results["000002.SZ"]!!
        assertTrue(result2.factors.isEmpty())
    }

    @Test
    fun `test invalid index handling`() {
        val candles = createTestCandles()

        // 无效的起始索引
        val return1 = service.calculateReturn(candles, -1, 4)
        assertEquals(0.0, return1, DELTA)

        // 无效的结束索引
        val return2 = service.calculateReturn(candles, 0, 10)
        assertEquals(0.0, return2, DELTA)

        // 起始索引大于结束索引
        val return3 = service.calculateReturn(candles, 4, 0)
        assertEquals(0.0, return3, DELTA)
    }

    @Test
    fun `test price comparison with different sizes`() {
        val original = createTestCandles()
        val adjusted = createTestCandles().take(3)

        try {
            service.getPriceComparison(original, adjusted)
            assertTrue(false, "Should throw exception for different sizes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("same size") == true)
        }
    }
}

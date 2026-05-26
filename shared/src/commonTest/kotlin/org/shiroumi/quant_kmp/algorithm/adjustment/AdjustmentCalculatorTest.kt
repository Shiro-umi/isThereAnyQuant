package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 复权算法单元测试
 */
class AdjustmentCalculatorTest {

    // 测试精度
    private companion object {
        const val DELTA = 1e-4
    }

    /**
     * 创建测试用的K线数据
     */
    private fun createTestCandles(): List<OhlcvCandle> {
        return listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0,
                high = 105.0,
                low = 98.0,
                close = 102.0,
                volume = 10000.0,
                amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0,
                high = 108.0,
                low = 101.0,
                close = 106.0,
                volume = 12000.0,
                amount = 1272000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 3),
                open = 106.0,
                high = 110.0,
                low = 104.0,
                close = 108.0,
                volume = 15000.0,
                amount = 1620000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 4),
                open = 108.0,
                high = 112.0,
                low = 107.0,
                close = 110.0,
                volume = 11000.0,
                amount = 1210000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 5),
                open = 110.0,
                high = 115.0,
                low = 109.0,
                close = 114.0,
                volume = 13000.0,
                amount = 1482000.0
            )
        )
    }

    @Test
    fun `test dividend adjustment calculation`() {
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
        }

        assertEquals(1, events.size)
        assertTrue(events[0] is AdjustmentEvent.Dividend)

        val dividend = events[0] as AdjustmentEvent.Dividend
        assertEquals(2.0, dividend.cashPerShare, DELTA)
    }

    @Test
    fun `test stock split adjustment calculation`() {
        val events = adjustmentEvents {
            stockSplit(LocalDate(2024, 1, 3), 0.5, "10送5")
        }

        assertEquals(1, events.size)
        assertTrue(events[0] is AdjustmentEvent.StockSplit)

        val split = events[0] as AdjustmentEvent.StockSplit
        assertEquals(0.5, split.ratio, DELTA)
    }

    @Test
    fun `test rights issue adjustment calculation`() {
        val events = adjustmentEvents {
            rightsIssue(LocalDate(2024, 1, 3), 0.3, 8.0, "10配3，配股价8元")
        }

        assertEquals(1, events.size)
        assertTrue(events[0] is AdjustmentEvent.RightsIssue)

        val rights = events[0] as AdjustmentEvent.RightsIssue
        assertEquals(0.3, rights.ratio, DELTA)
        assertEquals(8.0, rights.price, DELTA)
    }

    @Test
    fun `test composite adjustment event`() {
        val events = adjustmentEvents {
            composite(
                date = LocalDate(2024, 1, 3),
                dividend = 1.0,
                stockSplitRatio = 0.5,
                rightsIssueRatio = 0.2,
                rightsIssuePrice = 9.0,
                description = "综合除权除息"
            )
        }

        assertEquals(1, events.size)
        assertTrue(events[0] is AdjustmentEvent.Composite)

        val composite = events[0] as AdjustmentEvent.Composite
        assertEquals(1.0, composite.dividend, DELTA)
        assertEquals(0.5, composite.stockSplitRatio, DELTA)
        assertEquals(0.2, composite.rightsIssueRatio, DELTA)
        assertEquals(9.0, composite.rightsIssuePrice, DELTA)
    }

    @Test
    fun `test forward adjustment with dividend only`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
        }

        val result = candles.forwardAdjust(events, 114.0)

        // 验证结果类型
        assertEquals(AdjustmentType.FORWARD, result.type)
        assertEquals(5, result.candles.size)

        // 前复权：1月3日及之前的价格应该被调低
        // 复权因子 = (108 - 2) / 108 = 0.98148
        val original = candles[0]
        val adjusted = result.candles[0]

        // 开盘价应该降低
        assertTrue(adjusted.open < original.open)
        assertTrue(adjusted.close < original.close)

        // 成交量应该增加（保持金额不变）
        assertTrue(adjusted.volume > original.volume)
    }

    @Test
    fun `test backward adjustment with dividend only`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
        }

        val result = candles.backwardAdjust(events, 114.0)

        // 验证结果类型
        assertEquals(AdjustmentType.BACKWARD, result.type)
        assertEquals(5, result.candles.size)

        // 后复权：1月3日之后的价格应该被调高
        val original = candles[4]
        val adjusted = result.candles[4]

        // 收盘价应该升高
        assertTrue(adjusted.close > original.close)

        // 成交量保持不变
        assertEquals(original.volume, adjusted.volume, DELTA)
    }

    @Test
    fun `test forward adjustment with stock split`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            stockSplit(LocalDate(2024, 1, 3), 0.5, "10送5")
        }

        val result = candles.forwardAdjust(events, 114.0)

        // 送股后股数增加50%，每股价格应该降低
        // 复权因子 = 1 / 1.5 = 0.6667
        val original = candles[0]
        val adjusted = result.candles[0]

        // 价格应该降低约33%
        assertEquals(original.open * 0.6667, adjusted.open, 0.01)
        assertEquals(original.close * 0.6667, adjusted.close, 0.01)

        // 成交量应该增加50%
        assertEquals(original.volume * 1.5, adjusted.volume, 0.01)
    }

    @Test
    fun `test backward adjustment with stock split`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            stockSplit(LocalDate(2024, 1, 3), 0.5, "10送5")
        }

        val result = candles.backwardAdjust(events, 114.0)

        // 后复权：1月3日之后的价格应该升高
        val original = candles[4]
        val adjusted = result.candles[4]

        // 价格应该升高50%
        assertEquals(original.open * 1.5, adjusted.open, 0.01)
        assertEquals(original.close * 1.5, adjusted.close, 0.01)
    }

    @Test
    fun `test forward adjustment with multiple events`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 2), 1.0, "每股派息1元")
            stockSplit(LocalDate(2024, 1, 4), 0.3, "10送3")
        }

        val result = candles.forwardAdjust(events, 114.0)

        assertEquals(AdjustmentType.FORWARD, result.type)
        assertEquals(5, result.candles.size)
        assertEquals(2, result.factors.size)

        // 验证复权因子的累积效果
        val firstFactor = result.factors[0]
        val secondFactor = result.factors[1]

        assertTrue(firstFactor.forwardFactor < 1.0)
        assertTrue(secondFactor.forwardFactor < firstFactor.forwardFactor)
    }

    @Test
    fun `test backward adjustment with multiple events`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 2), 1.0, "每股派息1元")
            stockSplit(LocalDate(2024, 1, 4), 0.3, "10送3")
        }

        val result = candles.backwardAdjust(events, 114.0)

        assertEquals(AdjustmentType.BACKWARD, result.type)
        assertEquals(5, result.candles.size)

        // 后复权因子应该递增
        val firstFactor = result.factors[0]
        val secondFactor = result.factors[1]

        assertTrue(firstFactor.backwardFactor > 1.0)
        assertTrue(secondFactor.backwardFactor > firstFactor.backwardFactor)
    }

    @Test
    fun `test composite adjustment event calculation`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            composite(
                date = LocalDate(2024, 1, 3),
                dividend = 1.0,
                stockSplitRatio = 0.5,
                description = "10送5派1元"
            )
        }

        val forwardResult = candles.forwardAdjust(events, 114.0)
        val backwardResult = candles.backwardAdjust(events, 114.0)

        assertEquals(1, forwardResult.factors.size)
        assertEquals(1, backwardResult.factors.size)

        // 验证前复权因子小于1（价格降低）
        assertTrue(forwardResult.factors[0].forwardFactor < 1.0)

        // 验证后复权因子大于1（价格升高）
        assertTrue(backwardResult.factors[0].backwardFactor > 1.0)
    }

    @Test
    fun `test adjustment with empty events`() {
        val candles = createTestCandles()
        val events = emptyList<AdjustmentEvent>()

        val forwardResult = candles.forwardAdjust(events, 114.0)
        val backwardResult = candles.backwardAdjust(events, 114.0)

        // 空事件列表时，价格应该保持不变
        assertEquals(candles.size, forwardResult.candles.size)
        assertEquals(candles.size, backwardResult.candles.size)

        candles.forEachIndexed { index, original ->
            assertEquals(original.open, forwardResult.candles[index].open, DELTA)
            assertEquals(original.close, backwardResult.candles[index].close, DELTA)
        }
    }

    @Test
    fun `test adjustment with empty candles`() {
        val candles = emptyList<OhlcvCandle>()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
        }

        val result = candles.forwardAdjust(events, 114.0)

        assertTrue(result.candles.isEmpty())
        assertEquals(1, result.factors.size)
    }

    @Test
    fun `test adjustment factor calculation correctness`() {
        // 测试复权因子的精确计算
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 15), 2.0, "每股派息2元")
            stockSplit(LocalDate(2024, 2, 15), 0.5, "10送5")
        }

        val factors = AdjustmentCalculator.calculateFactors(events, 100.0)

        assertEquals(2, factors.size)

        // 第一个因子：分红
        // 前复权因子 = 1 - 2/100 = 0.98
        assertEquals(0.98, factors[0].forwardFactor, 0.001)
        // 后复权因子 = 100 / 98 = 1.0204
        assertEquals(1.0204, factors[0].backwardFactor, 0.001)

        // 第二个因子：送股（基于第一个因子的累积）
        // 前复权因子 = 0.98 / 1.5 = 0.6533
        assertEquals(0.6533, factors[1].forwardFactor, 0.001)
        // 后复权因子 = 1.0204 * 1.5 = 1.5306
        assertEquals(1.5306, factors[1].backwardFactor, 0.001)
    }

    @Test
    fun `test rights issue adjustment`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            rightsIssue(LocalDate(2024, 1, 3), 0.3, 8.0, "10配3，配股价8元")
        }

        val result = candles.forwardAdjust(events, 114.0)

        assertEquals(AdjustmentType.FORWARD, result.type)
        assertEquals(1, result.factors.size)

        // 配股价格低于市价，应该产生复权调整
        val factor = result.factors[0]
        assertTrue(factor.forwardFactor < 1.0)
        assertTrue(factor.backwardFactor > 1.0)
    }

    @Test
    fun `test DSL builder event ordering`() {
        // 测试DSL构建器是否正确按日期排序
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 3, 1), 1.0, "3月分红")
            dividend(LocalDate(2024, 1, 1), 2.0, "1月分红")
            dividend(LocalDate(2024, 2, 1), 1.5, "2月分红")
        }

        assertEquals(3, events.size)
        assertEquals(LocalDate(2024, 1, 1), events[0].date)
        assertEquals(LocalDate(2024, 2, 1), events[1].date)
        assertEquals(LocalDate(2024, 3, 1), events[2].date)
    }

    @Test
    fun `test adjustable candle interface`() {
        // 测试自定义 AdjustableCandle 实现
        val customCandle = TestCustomCandle(
            date = LocalDate(2024, 1, 1),
            open = 100.0,
            high = 110.0,
            low = 95.0,
            close = 105.0,
            volume = 10000.0,
            amount = 1050000.0,
            customField = "test"
        )

        val adjusted = customCandle.withAdjustedPrices(
            open = 50.0,
            high = 55.0,
            low = 47.5,
            close = 52.5,
            volume = 20000.0,
            amount = 1050000.0
        )

        assertTrue(adjusted is TestCustomCandle)
        assertEquals(50.0, adjusted.open, DELTA)
        assertEquals(52.5, adjusted.close, DELTA)
        assertEquals("test", adjusted.customField) // 自定义字段应该保留
    }

    @Test
    fun `test adjust function with type parameter`() {
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
        }

        val forwardResult = candles.adjust(AdjustmentType.FORWARD, events, 114.0)
        val backwardResult = candles.adjust(AdjustmentType.BACKWARD, events, 114.0)

        assertEquals(AdjustmentType.FORWARD, forwardResult.type)
        assertEquals(AdjustmentType.BACKWARD, backwardResult.type)

        // 验证两种复权方式产生不同的结果
        assertTrue(forwardResult.candles[0].close != backwardResult.candles[0].close)
    }

    @Test
    fun `test price relationship preservation`() {
        // 测试复权后价格关系是否保持（high >= open, close, low 等）
        val candles = createTestCandles()
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "每股派息2元")
            stockSplit(LocalDate(2024, 1, 4), 0.5, "10送5")
        }

        val result = candles.forwardAdjust(events, 114.0)

        result.candles.forEach { candle ->
            assertTrue(
                candle.high >= candle.open,
                "High($candle.high) should >= Open($candle.open)"
            )
            assertTrue(
                candle.high >= candle.close,
                "High should >= Close"
            )
            assertTrue(
                candle.low <= candle.open,
                "Low should <= Open"
            )
            assertTrue(
                candle.low <= candle.close,
                "Low should <= Close"
            )
            assertTrue(
                candle.high >= candle.low,
                "High should >= Low"
            )
        }
    }

    @Test
    fun `test volume and amount consistency`() {
        // 测试成交量和成交额的一致性
        val candles = createTestCandles()
        val events = adjustmentEvents {
            stockSplit(LocalDate(2024, 1, 3), 0.5, "10送5")
        }

        val result = candles.forwardAdjust(events, 114.0)

        result.candles.forEach { candle ->
            // 调整后金额应该约等于 收盘价 * 成交量
            val calculatedAmount = candle.close * candle.volume
            assertEquals(
                calculatedAmount,
                candle.amount,
                calculatedAmount * 0.001 // 允许0.1%的误差
            )
        }
    }

    // 自定义 AdjustableCandle 实现，用于测试接口
    private data class TestCustomCandle(
        override val date: LocalDate,
        override val open: Double,
        override val high: Double,
        override val low: Double,
        override val close: Double,
        override val volume: Double,
        override val amount: Double,
        val customField: String
    ) : AdjustableCandle {
        override fun withAdjustedPrices(
            open: Double,
            high: Double,
            low: Double,
            close: Double,
            volume: Double,
            amount: Double
        ): AdjustableCandle = copy(
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            amount = amount
        )
    }
}

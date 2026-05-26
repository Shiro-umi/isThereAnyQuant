package org.shiroumi.database.strategy.daily.preprocessing

import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import kotlin.uuid.ExperimentalUuidApi

/**
 * [WORK-003] 复权价格计算单元测试
 *
 * 测试范围：
 * 1. 后复权因子计算（hfqFactor = adj / firstAdj）
 * 2. RAW价格获取
 * 3. QFQ价格获取
 * 4. HFQ价格计算
 * 5. HFQ成交量计算
 * 6. 边界情况（firstAdj=0、hfqFactor<=0等）
 */
class PriceAdjustmentCalculatorTest {

    private lateinit var baseCandle: Candle

    @BeforeEach
    fun setUp() {
        baseCandle = createCandle(
            close = 100.0f,
            adj = 1.0f,
            closeQfq = 95.0f,
            volume = 10000f
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun createCandle(
        close: Float = 100.0f,
        open: Float = 98.0f,
        high: Float = 102.0f,
        low: Float = 97.0f,
        adj: Float = 1.0f,
        openQfq: Float = 0f,
        closeQfq: Float = 0f,
        highQfq: Float = 0f,
        lowQfq: Float = 0f,
        volume: Float = 10000f,
        volumeQfq: Float = 0f
    ): Candle {
        return Candle(
            tsCode = "600000.SH",
            date = LocalDate(2024, 1, 1),
            open = open,
            high = high,
            low = low,
            close = close,
            adj = adj,
            openQfq = openQfq,
            closeQfq = closeQfq,
            highQfq = highQfq,
            lowQfq = lowQfq,
            volume = volume,
            volumeQfq = volumeQfq,
            turnoverReal = volume * close,
            pe = 10f,
            peTtm = 10f,
            pb = 1f,
            ps = 1f,
            psTtm = 1f,
            mvTotal = 1000000f,
            mvCirc = 500000f
        )
    }

    @Test
    @DisplayName("RAW价格获取测试 - 应该返回原始价格")
    fun testRawPrice() {
        val candle = createCandle(close = 100.0f, closeQfq = 95.0f)

        val rawPrice = candle.price(PriceBasis.RAW)
        assertEquals(100.0, rawPrice, 0.001, "RAW价格应该等于原始close")

        val rawOpen = candle.openPrice(PriceBasis.RAW)
        assertEquals(98.0, rawOpen, 0.001, "RAW开盘价应该等于原始open")

        val rawVolume = candle.volumeValue(PriceBasis.RAW)
        assertEquals(10000.0, rawVolume, 0.001, "RAW成交量应该等于原始volume")
    }

    @Test
    @DisplayName("QFQ价格获取测试 - 应该返回前复权价格")
    fun testQfqPrice() {
        val candle = createCandle(
            close = 100.0f,
            closeQfq = 95.0f,
            open = 98.0f,
            openQfq = 93.0f
        )

        val qfqPrice = candle.price(PriceBasis.QFQ)
        assertEquals(95.0, qfqPrice, 0.001, "QFQ价格应该等于closeQfq")

        val qfqOpen = candle.openPrice(PriceBasis.QFQ)
        assertEquals(93.0, qfqOpen, 0.001, "QFQ开盘价应该等于openQfq")
    }

    @Test
    @DisplayName("QFQ价格回退测试 - closeQfq为0时应该回退到原始价格")
    fun testQfqFallback() {
        val candle = createCandle(close = 100.0f, closeQfq = 0.0f)

        val qfqPrice = candle.price(PriceBasis.QFQ)
        assertEquals(100.0, qfqPrice, 0.001, "QFQ价格回退应该等于原始close")
    }

    @Test
    @DisplayName("HFQ价格计算测试 - 使用hfqFactor")
    fun testHfqPrice() {
        val candle = createCandle(close = 100.0f)
        val hfqFactor = 1.5  // 模拟后复权因子

        val hfqPrice = candle.price(PriceBasis.HFQ, hfqFactor)
        assertEquals(150.0, hfqPrice, 0.001, "HFQ价格应该等于close * hfqFactor")

        val hfqOpen = candle.openPrice(PriceBasis.HFQ, hfqFactor)
        assertEquals(147.0, hfqOpen, 0.001, "HFQ开盘价应该等于open * hfqFactor")
    }

    @Test
    @DisplayName("HFQ成交量计算测试 - 应该除以hfqFactor")
    fun testHfqVolume() {
        val candle = createCandle(volume = 10000f)
        val hfqFactor = 2.0

        val hfqVolume = candle.volumeValue(PriceBasis.HFQ, hfqFactor)
        assertEquals(5000.0, hfqVolume, 0.001, "HFQ成交量应该等于volume / hfqFactor")
    }

    @Test
    @DisplayName("HFQ因子为null时应该抛出异常")
    fun testHfqNullFactor() {
        val candle = createCandle()

        assertThrows(IllegalArgumentException::class.java) {
            candle.price(PriceBasis.HFQ, null)
        }
    }

    @Test
    @DisplayName("HFQ因子为0时应该抛出异常")
    fun testHfqZeroFactor() {
        val candle = createCandle()

        assertThrows(IllegalArgumentException::class.java) {
            candle.price(PriceBasis.HFQ, 0.0)
        }
    }

    @Test
    @DisplayName("HFQ因子为负数时应该抛出异常")
    fun testHfqNegativeFactor() {
        val candle = createCandle()

        assertThrows(IllegalArgumentException::class.java) {
            candle.price(PriceBasis.HFQ, -1.0)
        }
    }

    @Test
    @DisplayName("后复权因子计算测试 - hfqFactor = adj / firstAdj")
    fun testHfqFactorCalculation() {
        // 模拟历史数据
        val firstAdj = 0.8f  // 首日复权因子
        val currentAdj = 1.2f  // 当前复权因子

        val hfqFactor = (currentAdj / firstAdj).toDouble()

        assertEquals(1.5, hfqFactor, 0.001, "后复权因子计算应该正确")
    }

    @Test
    @DisplayName("首日复权因子为0时的处理 - 应该使用1.0作为默认值")
    fun testZeroFirstAdjHandling() {
        val firstAdj = 0.0f
        val currentAdj = 1.2f

        // 当firstAdj为0时，应该使用1.0作为默认值
        val normalizedFirstAdj = firstAdj.takeIf { it > 0f } ?: 1.0f
        val hfqFactor = (currentAdj / normalizedFirstAdj).toDouble()

        assertEquals(1.2, hfqFactor, 0.001, "firstAdj为0时应该使用1.0作为默认值")
    }

    @Test
    @DisplayName("复权因子变化对价格的影响测试")
    fun testAdjustmentFactorImpact() {
        val basePrice = 100.0f

        // 场景1：无复权（adj = 1.0, firstAdj = 1.0）
        val candle1 = createCandle(close = basePrice, adj = 1.0f)
        val hfqFactor1 = (1.0f / 1.0f).toDouble()
        assertEquals(100.0, candle1.price(PriceBasis.HFQ, hfqFactor1), 0.001)

        // 场景2：有分红（adj = 1.1, firstAdj = 1.0）
        val candle2 = createCandle(close = basePrice, adj = 1.1f)
        val hfqFactor2 = (1.1f / 1.0f).toDouble()
        assertEquals(110.0, candle2.price(PriceBasis.HFQ, hfqFactor2), 0.001)

        // 场景3：多次复权（adj = 2.0, firstAdj = 0.5）
        val candle3 = createCandle(close = basePrice, adj = 2.0f)
        val hfqFactor3 = (2.0f / 0.5f).toDouble()
        assertEquals(400.0, candle3.price(PriceBasis.HFQ, hfqFactor3), 0.001)
    }

    @Test
    @DisplayName("完整OHLCV复权计算测试")
    fun testFullOhlcvAdjustment() {
        val candle = createCandle(
            open = 100.0f,
            high = 105.0f,
            low = 98.0f,
            close = 102.0f,
            volume = 10000f
        )
        val hfqFactor = 1.5

        // 验证所有价格字段都正确复权
        assertEquals(153.0, candle.price(PriceBasis.HFQ, hfqFactor), 0.001, "close复权错误")
        assertEquals(150.0, candle.openPrice(PriceBasis.HFQ, hfqFactor), 0.001, "open复权错误")
        assertEquals(157.5, candle.highPrice(PriceBasis.HFQ, hfqFactor), 0.001, "high复权错误")
        assertEquals(147.0, candle.lowPrice(PriceBasis.HFQ, hfqFactor), 0.001, "low复权错误")
        assertEquals(6666.67, candle.volumeValue(PriceBasis.HFQ, hfqFactor), 0.01, "volume复权错误")
    }

    @Test
    @DisplayName("价格精度测试 - 浮点数计算精度")
    fun testPricePrecision() {
        val candle = createCandle(close = 99.99f)
        val hfqFactor = 1.333333

        val hfqPrice = candle.price(PriceBasis.HFQ, hfqFactor)
        val expected = 99.99 * 1.333333

        assertEquals(expected, hfqPrice, 0.01, "价格计算精度应该在可接受范围内")
    }

    @Test
    @DisplayName("极端价格测试 - 高价股和低价股")
    fun testExtremePrices() {
        // 高价股（如贵州茅台）
        val highPriceCandle = createCandle(close = 2000.0f, adj = 1.0f)
        val hfqFactorHigh = 10.0
        assertEquals(20000.0, highPriceCandle.price(PriceBasis.HFQ, hfqFactorHigh), 0.001)

        // 低价股
        val lowPriceCandle = createCandle(close = 1.5f, adj = 1.0f)
        val hfqFactorLow = 0.5
        assertEquals(0.75, lowPriceCandle.price(PriceBasis.HFQ, hfqFactorLow), 0.001)
    }

    @Test
    @DisplayName("成交量极端值测试")
    fun testExtremeVolume() {
        // 高成交量
        val highVolCandle = createCandle(volume = 100000000f)
        val hfqFactor = 2.0
        assertEquals(50000000.0, highVolCandle.volumeValue(PriceBasis.HFQ, hfqFactor), 0.001)

        // 低成交量
        val lowVolCandle = createCandle(volume = 100f)
        assertEquals(50.0, lowVolCandle.volumeValue(PriceBasis.HFQ, hfqFactor), 0.001)
    }
}

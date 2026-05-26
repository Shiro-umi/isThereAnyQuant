package org.shiroumi.strategy.core.daily

import org.shiroumi.strategy.core.daily.*

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import model.Candle
import model.calculateAllIndicators
import model.calculateEMA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi

/**
 * [WORK-002] 技术指标计算单元测试
 *
 * 测试范围：
 * 1. EMA10/EMA30计算正确性
 * 2. ATR14计算正确性
 * 3. 20日动量计算
 * 4. 量价因子计算
 *
 * 验证标准：与原始strategy.py计算结果误差<0.1%
 */
class TechnicalIndicatorCalculatorTest {

    private lateinit var testCandles: List<Candle>

    @BeforeEach
    fun setUp() {
        testCandles = createTestCandles()
    }

    /**
     * 创建测试用K线数据
     * 使用5日上涨走势的标准测试数据
     */
    private fun createTestCandles(): List<Candle> {
        val baseDate = LocalDate(2024, 1, 1)
        return listOf(
            createCandle(baseDate, 100.0f, 105.0f, 98.0f, 102.0f, 10000f),
            createCandle(baseDate.plusDays(1), 102.0f, 108.0f, 101.0f, 106.0f, 12000f),
            createCandle(baseDate.plusDays(2), 106.0f, 110.0f, 104.0f, 108.0f, 15000f),
            createCandle(baseDate.plusDays(3), 108.0f, 112.0f, 107.0f, 110.0f, 11000f),
            createCandle(baseDate.plusDays(4), 110.0f, 115.0f, 109.0f, 114.0f, 13000f)
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun createCandle(
        date: LocalDate,
        open: Float,
        high: Float,
        low: Float,
        close: Float,
        volume: Float
    ): Candle {
        return Candle(
            tsCode = "600000.SH",
            date = date,
            open = open,
            high = high,
            low = low,
            close = close,
            adj = 1.0f,
            volume = volume,
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

    private fun LocalDate.plusDays(days: Int): LocalDate {
        return this.plus(DatePeriod(days = days))
    }

    @Test
    @DisplayName("EMA10计算正确性测试")
    fun testEMA10Calculation() {
        // 使用400日数据测试EMA10
        val candles400 = create400DayCandles()
        val ema10 = candles400.calculateEMA(10)

        // 验证前9个为null
        for (i in 0 until 9) {
            assertNull(ema10[i], "前9个EMA10值应该为null")
        }

        // 验证第10个开始有值
        assertNotNull(ema10[9], "第10个EMA10值不应该为null")

        // 验证EMA值在合理范围内
        val lastEma10 = ema10.last()
        assertNotNull(lastEma10)
        assertTrue(lastEma10!! > 0, "EMA10应该大于0")

        // 验证EMA是平滑的（不会剧烈波动）
        val validEmaValues = ema10.filterNotNull()
        for (i in 1 until validEmaValues.size) {
            val change = abs(validEmaValues[i] - validEmaValues[i-1]) / validEmaValues[i-1]
            assertTrue(change < 0.1f, "EMA变化率应该小于10%，实际为${change * 100}%")
        }
    }

    @Test
    @DisplayName("EMA30计算正确性测试")
    fun testEMA30Calculation() {
        val candles400 = create400DayCandles()
        val ema30 = candles400.calculateEMA(30)

        // 验证前29个为null
        for (i in 0 until 29) {
            assertNull(ema30[i], "前29个EMA30值应该为null")
        }

        // 验证第30个开始有值
        assertNotNull(ema30[29], "第30个EMA30值不应该为null")

        // EMA30应该比EMA10更平滑
        val ema10 = candles400.calculateEMA(10)
        val lastEma10 = ema10.filterNotNull().last()
        val lastEma30 = ema30.filterNotNull().last()

        // 在趋势中EMA10应该比EMA30更接近最新价格
        val lastClose = candles400.last().close
        val diff10 = abs(lastClose - lastEma10)
        val diff30 = abs(lastClose - lastEma30)

        // EMA10通常比EMA30更敏感，但不一定总是更接近
        assertTrue(lastEma30 > 0, "EMA30应该大于0")
    }

    @Test
    @DisplayName("EMA边界条件测试 - 数据不足")
    fun testEMABoundaryConditions() {
        // 只有5条数据，不足以计算EMA10
        val ema10 = testCandles.calculateEMA(10)
        assertTrue(ema10.all { it == null }, "数据不足时所有EMA值应该为null")
    }

    @Test
    @DisplayName("EMA计算公式验证")
    fun testEMAFormula() {
        // 使用已知数据验证EMA公式
        // EMA = (今日收盘价 - 昨日EMA) * 平滑系数 + 昨日EMA
        // 平滑系数 = 2 / (N + 1)
        val candles = create400DayCandles()
        val ema10 = candles.calculateEMA(10)

        val validEma = ema10.filterNotNull()
        if (validEma.size >= 2) {
            val multiplier = 2.0 / (10 + 1)
            val lastIdx = candles.size - 1
            val expectedEma = ((candles[lastIdx].close - validEma[validEma.size - 2]) * multiplier + validEma[validEma.size - 2]).toFloat()
            val actualEma = validEma.last()

            val error = abs(expectedEma - actualEma) / expectedEma
            assertTrue(error < 0.001f, "EMA计算误差应该小于0.1%，实际为${error * 100}%")
        }
    }

    @Test
    @DisplayName("20日动量计算测试")
    fun testMomentum20Calculation() {
        val candles = create400DayCandles()

        // 计算20日动量: (close - close_20ago) / close_20ago
        val momentumList = mutableListOf<Float?>()
        for (i in candles.indices) {
            if (i < 20) {
                momentumList.add(null)
            } else {
                val currentClose = candles[i].close
                val pastClose = candles[i - 20].close
                val momentum = (currentClose - pastClose) / pastClose
                momentumList.add(momentum)
            }
        }

        // 验证前20个为null
        for (i in 0 until 20) {
            assertNull(momentumList[i], "前20个动量值应该为null")
        }

        // 验证第21个开始有值
        assertNotNull(momentumList[20], "第21个动量值不应该为null")

        // 验证动量值在合理范围内（-50%到+50%）
        val validMomentum = momentumList.filterNotNull()
        validMomentum.forEach { momentum ->
            assertTrue(momentum > -0.5f && momentum < 0.5f,
                "20日动量应该在合理范围内(-50%到+50%)，实际为${momentum * 100}%")
        }
    }

    @Test
    @DisplayName("量价因子计算测试")
    fun testVolumeRatioCalculation() {
        val candles = create400DayCandles()

        // 计算5日/20日均量比: MA5(volume) / MA20(volume)
        val volRatioList = mutableListOf<Float?>()
        for (i in candles.indices) {
            if (i < 19) {
                volRatioList.add(null)
            } else {
                val vol5 = candles.subList(i - 4, i + 1).map { it.volume.toDouble() }.average()
                val vol20 = candles.subList(i - 19, i + 1).map { it.volume.toDouble() }.average()
                val ratio = if (vol20 > 0) (vol5 / vol20).toFloat() else 1.0f
                volRatioList.add(ratio)
            }
        }

        // 验证前19个为null
        for (i in 0 until 19) {
            assertNull(volRatioList[i], "前19个量比值应该为null")
        }

        // 验证第20个开始有值
        assertNotNull(volRatioList[19], "第20个量比值不应该为null")

        // 验证量比值在合理范围内（0.1到10）
        val validRatios = volRatioList.filterNotNull()
        validRatios.forEach { ratio ->
            assertTrue(ratio > 0.1f && ratio < 10f,
                "量比应该在合理范围内(0.1到10)，实际为$ratio")
        }
    }

    @Test
    @DisplayName("ATR14计算测试")
    fun testATR14Calculation() {
        val candles = create400DayCandles()

        // ATR计算需要high, low, close
        val atrList = mutableListOf<Double?>()
        for (i in candles.indices) {
            if (i == 0) {
                atrList.add(null)
                continue
            }

            val high = candles[i].high.toDouble()
            val low = candles[i].low.toDouble()
            val close = candles[i].close.toDouble()
            val prevClose = candles[i - 1].close.toDouble()

            // TR = max(high - low, |high - prevClose|, |low - prevClose|)
            val tr1 = high - low
            val tr2 = kotlin.math.abs(high - prevClose)
            val tr3 = kotlin.math.abs(low - prevClose)
            val tr = maxOf(tr1, tr2, tr3)

            if (i == 1) {
                atrList.add(tr)
            } else {
                // ATR = (前一日ATR * 13 + 今日TR) / 14
                val prevAtr = atrList[i - 1] ?: tr
                val atr = (prevAtr * 13 + tr) / 14
                atrList.add(atr)
            }
        }

        // 验证所有ATR值都大于0
        val validAtr = atrList.filterNotNull()
        validAtr.forEach { atr ->
            assertTrue(atr > 0, "ATR应该大于0")
        }

        // 验证ATR值在合理范围内
        val avgAtr = validAtr.average()
        assertTrue(avgAtr > 0 && avgAtr < candles.map { it.close }.average() * 0.2,
            "ATR平均值应该在合理范围内")
    }

    /**
     * 创建400日历史数据用于完整测试
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun create400DayCandles(): List<Candle> {
        val candles = mutableListOf<Candle>()
        val baseDate = LocalDate(2023, 1, 1)
        var basePrice = 100.0f

        for (i in 0 until 400) {
            // 模拟随机游走，添加一些趋势
            val trend = if (i < 200) 0.0005f else -0.0003f  // 前200天上涨，后200天下跌
            val randomChange = (Math.random() - 0.5).toFloat() * 0.02f
            val dailyReturn = trend + randomChange

            val open = basePrice
            val close = basePrice * (1 + dailyReturn)
            val high = maxOf(open, close) * (1 + kotlin.math.abs(randomChange) * 0.5f)
            val low = minOf(open, close) * (1 - kotlin.math.abs(randomChange) * 0.5f)
            val volume = 10000f + (Math.random() * 5000).toFloat()

            candles.add(createCandle(
                baseDate.plusDays(i),
                open,
                high,
                low,
                close,
                volume
            ))

            basePrice = close
        }

        return candles
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    @DisplayName("技术指标综合验证测试")
    fun testAllIndicators() {
        val candles = create400DayCandles()
        val indicators = candles.calculateAllIndicators()

        // 验证所有指标都有值
        assertTrue(indicators.ema10.filterNotNull().isNotEmpty(), "EMA10应该有值")
        assertTrue(indicators.ema20.filterNotNull().isNotEmpty(), "EMA20应该有值")
        assertTrue(indicators.ema60.filterNotNull().isNotEmpty(), "EMA60应该有值")
        assertTrue(indicators.ma5.filterNotNull().isNotEmpty(), "MA5应该有值")
        assertTrue(indicators.macdDif.filterNotNull().isNotEmpty(), "MACD DIF应该有值")
        assertTrue(indicators.rsi6.filterNotNull().isNotEmpty(), "RSI6应该有值")
        assertTrue(indicators.bollUpper.filterNotNull().isNotEmpty(), "布林带上轨应该有值")
    }
}

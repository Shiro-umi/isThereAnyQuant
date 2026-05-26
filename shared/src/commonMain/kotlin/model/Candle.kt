package model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 价格口径。
 * - RAW：原始未复权
 * - QFQ：前复权（当前项目已落表）
 * - HFQ：后复权（本项目策略侧采用运行时推导：hfqFactor = adj / firstAdj）
 */
@Serializable
enum class PriceBasis {
    RAW,
    QFQ,
    HFQ,
}

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Candle(
    val id: Uuid = Uuid.random(),
    val tsCode: String,
    val date: LocalDate,
    /**
     * 分钟线完整时间戳，格式 "2024-01-02 09:30:00"。
     * 日线/周线/月线为 null。
     */
    val tradeTime: String? = null,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val adj: Float,
    val openQfq: Float = 0f,
    val closeQfq: Float = 0f,
    val highQfq: Float = 0f,
    val lowQfq: Float = 0f,
    val volume: Float,
    val volumeQfq: Float = 0f,
    val turnoverReal: Float,
    val pe: Float,
    val peTtm: Float,
    val pb: Float,
    val ps: Float,
    val psTtm: Float,
    val mvTotal: Float,
    val mvCirc: Float
) {
    /**
     * 是否为阳线
     */
    val isBullish: Boolean
        get() = close >= open

    /**
     * 实体大小
     */
    val bodySize: Float
        get() = abs(close - open)

    /**
     * 上影线长度
     */
    val upperShadow: Float
        get() = high - maxOf(open, close)

    /**
     * 下影线长度
     */
    val lowerShadow: Float
        get() = minOf(open, close) - low

    /**
     * 总振幅
     */
    val totalRange: Float
        get() = high - low

    /**
     * 获取用于计算的价格（优先使用前复权）
     */
    fun getPrice(useAdjusted: Boolean = true): Float =
        if (useAdjusted && closeQfq > 0) closeQfq else close

    /**
     * 获取开盘价（优先使用前复权）
     */
    fun getOpen(useAdjusted: Boolean = true): Float =
        if (useAdjusted && openQfq > 0) openQfq else open

    /**
     * 获取最高价（优先使用前复权）
     */
    fun getHigh(useAdjusted: Boolean = true): Float =
        if (useAdjusted && highQfq > 0) highQfq else high

    /**
     * 获取最低价（优先使用前复权）
     */
    fun getLow(useAdjusted: Boolean = true): Float =
        if (useAdjusted && lowQfq > 0) lowQfq else low

    fun price(basis: PriceBasis, hfqFactor: Double? = null): Double = when (basis) {
        PriceBasis.RAW -> close.toDouble()
        PriceBasis.QFQ -> if (closeQfq > 0f) closeQfq.toDouble() else close.toDouble()
        PriceBasis.HFQ -> close.toDouble() * requireHfqFactor(hfqFactor)
    }

    fun openPrice(basis: PriceBasis, hfqFactor: Double? = null): Double = when (basis) {
        PriceBasis.RAW -> open.toDouble()
        PriceBasis.QFQ -> if (openQfq > 0f) openQfq.toDouble() else open.toDouble()
        PriceBasis.HFQ -> open.toDouble() * requireHfqFactor(hfqFactor)
    }

    fun highPrice(basis: PriceBasis, hfqFactor: Double? = null): Double = when (basis) {
        PriceBasis.RAW -> high.toDouble()
        PriceBasis.QFQ -> if (highQfq > 0f) highQfq.toDouble() else high.toDouble()
        PriceBasis.HFQ -> high.toDouble() * requireHfqFactor(hfqFactor)
    }

    fun lowPrice(basis: PriceBasis, hfqFactor: Double? = null): Double = when (basis) {
        PriceBasis.RAW -> low.toDouble()
        PriceBasis.QFQ -> if (lowQfq > 0f) lowQfq.toDouble() else low.toDouble()
        PriceBasis.HFQ -> low.toDouble() * requireHfqFactor(hfqFactor)
    }

    fun volumeValue(basis: PriceBasis, hfqFactor: Double? = null): Double = when (basis) {
        PriceBasis.RAW -> volume.toDouble()
        PriceBasis.QFQ -> if (volumeQfq > 0f) volumeQfq.toDouble() else volume.toDouble()
        PriceBasis.HFQ -> volume.toDouble() / requireHfqFactor(hfqFactor)
    }

    private fun requireHfqFactor(hfqFactor: Double?): Double {
        require(hfqFactor != null && hfqFactor > 0.0) { "HFQ 口径需要有效的 hfqFactor" }
        return hfqFactor
    }
}

// ==================== 转换为传输层数据扩展 ====================

/**
 * 转换为CandleData（统一的数据传输对象）
 * @param useAdjusted 是否优先使用前复权价格
 * @param previous 前一根K线，用于计算涨跌幅和振幅
 * @return CandleData
 */
fun Candle.toCandleData(
    useAdjusted: Boolean = true,
    previous: Candle? = null
): CandleData {
    val (displayOpen, displayHigh, displayLow, displayClose) = if (useAdjusted && closeQfq > 0) {
        listOf(openQfq, highQfq, lowQfq, closeQfq)
    } else {
        listOf(open, high, low, close)
    }

    val changePct = previous?.let { ((displayClose - it.getPrice(useAdjusted)) / it.getPrice(useAdjusted)) * 100 }
    val amp = ((displayHigh - displayLow) / displayOpen) * 100

    return CandleData(
        date = date.toString(),
        open = displayOpen,
        high = displayHigh,
        low = displayLow,
        close = displayClose,
        volume = if (useAdjusted && volumeQfq > 0) volumeQfq else volume,
        turnover = turnoverReal,
        changePercent = changePct,
        amplitude = amp,
        adjOpen = if (openQfq > 0) open else null,
        adjHigh = if (highQfq > 0) high else null,
        adjLow = if (lowQfq > 0) low else null,
        adjClose = if (closeQfq > 0) close else null
    )
}

/**
 * 将Candle列表转换为CandleData列表
 * @param useAdjusted 是否优先使用前复权价格
 * @return List<CandleData>
 */
fun List<Candle>.toCandleDataList(useAdjusted: Boolean = true): List<CandleData> {
    return mapIndexed { index, candle ->
        candle.toCandleData(useAdjusted, if (index > 0) this[index - 1] else null)
    }
}

// ==================== 技术指标计算扩展 ====================

/**
 * 计算指数移动平均线 (EMA)
 * @param period 周期
 * @return EMA值列表，前period-1个为null
 */
fun List<Candle>.calculateEMA(period: Int): List<Float?> {
    if (size < period) return List(size) { null }

    val closes = map { it.close.toDouble() }
    val multiplier = 2.0 / (period + 1)
    val result = MutableList<Float?>(size) { null }

    // 第一个EMA值用简单平均
    var ema = closes.take(period).average()
    result[period - 1] = ema.toFloat()

    // 计算后续EMA值
    for (i in period until size) {
        ema = (closes[i] - ema) * multiplier + ema
        result[i] = ema.toFloat()
    }

    return result
}

/**
 * 计算简单移动平均线 (MA)
 * @param period 周期
 * @return MA值列表，前period-1个为null
 */
fun List<Candle>.calculateMA(period: Int): List<Float?> {
    if (size < period) return List(size) { null }

    val closes = map { it.close.toDouble() }
    return mapIndexed { index, _ ->
        if (index < period - 1) {
            null
        } else {
            closes.subList(index - period + 1, index + 1).average().toFloat()
        }
    }
}

/**
 * 计算相对强弱指标 (RSI)
 * @param period 周期
 * @return RSI值列表，前period个为null
 */
fun List<Candle>.calculateRSI(period: Int): List<Float?> {
    if (size < period + 1) return List(size) { null }

    val closes = map { it.close.toDouble() }
    val result = MutableList<Float?>(size) { null }

    for (i in period until size) {
        var gains = 0.0
        var losses = 0.0

        for (j in 1..period) {
            val change = closes[i - period + j] - closes[i - period + j - 1]
            if (change > 0) gains += change else losses += abs(change)
        }

        val avgGain = gains / period
        val avgLoss = losses / period

        result[i] = if (avgLoss == 0.0) {
            100.0f
        } else {
            val rs = avgGain / avgLoss
            (100.0 - (100.0 / (1.0 + rs))).toFloat()
        }
    }

    return result
}

/**
 * 计算MACD指标
 * @param fastPeriod 快线周期，默认12
 * @param slowPeriod 慢线周期，默认26
 * @param signalPeriod 信号线周期，默认9
 * @return Triple(DIF列表, DEA列表, BAR列表)
 */
fun List<Candle>.calculateMACD(
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): Triple<List<Float?>, List<Float?>, List<Float?>> {
    if (size < slowPeriod + signalPeriod) {
        return Triple(List(size) { null }, List(size) { null }, List(size) { null })
    }

    val closes = map { it.close.toDouble() }
    val difList = MutableList<Float?>(size) { null }
    val deaList = MutableList<Float?>(size) { null }
    val barList = MutableList<Float?>(size) { null }

    // 计算DIF (EMA12 - EMA26)
    val difValues = mutableListOf<Double>()
    for (i in slowPeriod - 1 until size) {
        val slice = closes.subList(0, i + 1)
        val ema12 = calculateEMAInternal(slice, fastPeriod)
        val ema26 = calculateEMAInternal(slice, slowPeriod)
        val dif = ema12 - ema26
        difValues.add(dif)
        difList[i] = dif.toFloat()
    }

    // 计算DEA (DIF的EMA)
    if (difValues.size >= signalPeriod) {
        var dea = difValues.take(signalPeriod).average()
        val deaMultiplier = 2.0 / (signalPeriod + 1)

        for (i in signalPeriod - 1 until difValues.size) {
            val actualIndex = slowPeriod - 1 + i
            if (i == signalPeriod - 1) {
                deaList[actualIndex] = dea.toFloat()
            } else {
                dea = (difValues[i] - dea) * deaMultiplier + dea
                deaList[actualIndex] = dea.toFloat()
            }
            // BAR = (DIF - DEA) * 2
            barList[actualIndex] = ((difValues[i] - dea) * 2).toFloat()
        }
    }

    return Triple(difList, deaList, barList)
}

/**
 * 计算布林带 (Bollinger Bands)
 * @param period 周期，默认20
 * @param multiplier 标准差倍数，默认2.0
 * @return Triple(上轨列表, 中轨列表, 下轨列表)
 */
fun List<Candle>.calculateBollingerBands(
    period: Int = 20,
    multiplier: Double = 2.0
): Triple<List<Float?>, List<Float?>, List<Float?>> {
    if (size < period) {
        return Triple(List(size) { null }, List(size) { null }, List(size) { null })
    }

    val closes = map { it.close.toDouble() }
    val upperList = MutableList<Float?>(size) { null }
    val midList = MutableList<Float?>(size) { null }
    val lowerList = MutableList<Float?>(size) { null }

    for (i in period - 1 until size) {
        val slice = closes.subList(i - period + 1, i + 1)
        val sma = slice.average()
        val variance = slice.map { (it - sma).pow(2) }.average()
        val stdDev = sqrt(variance)

        upperList[i] = (sma + multiplier * stdDev).toFloat()
        midList[i] = sma.toFloat()
        lowerList[i] = (sma - multiplier * stdDev).toFloat()
    }

    return Triple(upperList, midList, lowerList)
}

/**
 * 计算所有常用技术指标
 * 返回包含所有指标的数据类
 */
fun List<Candle>.calculateAllIndicators(): CandleIndicators {
    val ema5 = calculateEMA(5)
    val ema10 = calculateEMA(10)
    val ema20 = calculateEMA(20)
    val ema60 = calculateEMA(60)
    val ma5 = calculateMA(5)
    val ma10 = calculateMA(10)
    val ma20 = calculateMA(20)
    val ma60 = calculateMA(60)
    val rsi6 = calculateRSI(6)
    val rsi12 = calculateRSI(12)
    val rsi24 = calculateRSI(24)
    val (macdDif, macdDea, macdBar) = calculateMACD()
    val (bollUpper, bollMid, bollLower) = calculateBollingerBands()

    return CandleIndicators(
        ema5 = ema5, ema10 = ema10, ema20 = ema20, ema60 = ema60,
        ma5 = ma5, ma10 = ma10, ma20 = ma20, ma60 = ma60,
        rsi6 = rsi6, rsi12 = rsi12, rsi24 = rsi24,
        macdDif = macdDif, macdDea = macdDea, macdBar = macdBar,
        bollUpper = bollUpper, bollMid = bollMid, bollLower = bollLower
    )
}

/**
 * 技术指标数据类
 */
data class CandleIndicators(
    val ema5: List<Float?>,
    val ema10: List<Float?>,
    val ema20: List<Float?>,
    val ema60: List<Float?>,
    val ma5: List<Float?>,
    val ma10: List<Float?>,
    val ma20: List<Float?>,
    val ma60: List<Float?>,
    val rsi6: List<Float?>,
    val rsi12: List<Float?>,
    val rsi24: List<Float?>,
    val macdDif: List<Float?>,
    val macdDea: List<Float?>,
    val macdBar: List<Float?>,
    val bollUpper: List<Float?>,
    val bollMid: List<Float?>,
    val bollLower: List<Float?>
)

// ==================== 涨跌计算扩展 ====================

/**
 * 计算涨跌幅百分比
 * @param previous 前一根K线，为null时返回0
 * @return 涨跌幅百分比
 */
fun Candle.changePercent(previous: Candle?): Float {
    if (previous == null) return 0f
    return ((close - previous.close) / previous.close) * 100
}

/**
 * 计算日内振幅百分比
 * @return 振幅百分比
 */
fun Candle.amplitude(): Float {
    return ((high - low) / open) * 100
}

/**
 * 计算涨跌额
 * @param previous 前一根K线，为null时返回0
 * @return 涨跌额
 */
fun Candle.changeAmount(previous: Candle?): Float {
    if (previous == null) return 0f
    return close - previous.close
}

// ==================== 转换为展示层数据扩展 ====================

/**
 * 转换为CandleData（UI展示用）
 * @param previous 前一根K线，用于计算涨跌幅
 * @return CandleData
 */
fun Candle.toCandleData(previous: Candle? = null): model.candle.CandleData {
    return model.candle.CandleData(
        date = date.toString(),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        turnover = turnoverReal,
        changePercent = changePercent(previous)
    )
}

/**
 * 转换为CandleChartData（完整图表数据）
 * @param tsCode 股票代码
 * @param name 股票名称
 * @return CandleChartData
 */
fun List<Candle>.toCandleChartData(tsCode: String, name: String): model.candle.CandleChartData {
    if (isEmpty()) {
        return model.candle.CandleChartData(
            code = tsCode,
            name = name,
            candles = emptyList(),
            volumes = emptyList(),
            ema20 = emptyList(),
            rsi6 = emptyList(),
            macdDif = emptyList(),
            macdDea = emptyList(),
            macdBar = emptyList()
        )
    }

    val indicators = calculateAllIndicators()

    return model.candle.CandleChartData(
        code = tsCode,
        name = name,
        candles = mapIndexed { index, candle ->
            candle.toCandleData(if (index > 0) this[index - 1] else null)
        },
        volumes = map { it.volume },
        ema20 = indicators.ema20,
        rsi6 = indicators.rsi6,
        macdDif = indicators.macdDif,
        macdDea = indicators.macdDea,
        macdBar = indicators.macdBar,
        ema5 = indicators.ema5,
        ema10 = indicators.ema10,
        ema60 = indicators.ema60,
        rsi12 = indicators.rsi12,
        rsi24 = indicators.rsi24,
        ma5 = indicators.ma5,
        ma10 = indicators.ma10,
        ma20 = indicators.ma20,
        ma60 = indicators.ma60,
        bollUpper = indicators.bollUpper,
        bollMid = indicators.bollMid,
        bollLower = indicators.bollLower
    )
}

// ==================== 内部辅助函数 ====================

/**
 * 内部EMA计算（用于MACD计算）
 */
private fun calculateEMAInternal(data: List<Double>, period: Int): Double {
    if (data.isEmpty()) return 0.0
    if (data.size == 1) return data[0]

    val multiplier = 2.0 / (period + 1)
    var ema = data.take(period).average()

    for (i in period until data.size) {
        ema = (data[i] - ema) * multiplier + ema
    }

    return ema
}

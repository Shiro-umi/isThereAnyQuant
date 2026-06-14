package org.shiroumi.quant_kmp.data.candle

import kotlinx.coroutines.yield
import model.Candle
import model.candle.CandleChartData
import model.candle.CandleData

/**
 * 将 Candle 列表转换为 CandleChartData。
 *
 * 该转换属于共享数据层：把后端原始 K 线 + 技术指标计算聚合成 UI 可直接消费的图表数据。
 * 由 candle / strategytracking 等多个 feature 共用，故归置在 data.candle，不再藏在某个 feature 的 contract 里。
 */
fun List<Candle>.toCandleChartData(code: String, name: String): CandleChartData {
    if (isEmpty()) {
        return CandleChartData(
            code = code,
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

    // 转换为CandleData用于UI展示
    // 分钟线优先使用 tradeTime（完整时间戳），日线使用 date 字符串
    val candleDataList = mapIndexed { index, candle ->
        CandleData(
            date = candle.tradeTime ?: candle.date.toString(),
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume,
            turnover = candle.turnoverReal,
            changePercent = if (index > 0) {
                val prev = get(index - 1)
                if (prev.close != 0f) ((candle.close - prev.close) / prev.close * 100) else null
            } else null
        )
    }

    // 计算技术指标
    val closes = map { it.close.toDouble() }
    val volumes = map { it.volume }

    // EMA指标
    val ema5Values = calculateEMAList(closes, 5)
    val ema10Values = calculateEMAList(closes, 10)
    val ema20Values = calculateEMAList(closes, 20)
    val ema60Values = calculateEMAList(closes, 60)

    // MA指标
    val ma5Values = calculateSMAList(closes, 5)
    val ma10Values = calculateSMAList(closes, 10)
    val ma20Values = calculateSMAList(closes, 20)
    val ma60Values = calculateSMAList(closes, 60)

    // RSI指标
    val rsi6Values = calculateRSIList(closes, 6)
    val rsi12Values = calculateRSIList(closes, 12)
    val rsi24Values = calculateRSIList(closes, 24)

    // MACD指标
    val (macdDifValues, macdDeaValues, macdBarValues) = calculateMACDList(closes)

    // 布林带
    val (bollUpperValues, bollMidValues, bollLowerValues) = calculateBollingerBandsList(closes, 20, 2.0)

    return CandleChartData(
        code = code,
        name = name,
        candles = candleDataList,
        volumes = volumes,
        ema20 = ema20Values,
        rsi6 = rsi6Values,
        macdDif = macdDifValues,
        macdDea = macdDeaValues,
        macdBar = macdBarValues,
        // 扩展指标
        ema5 = ema5Values,
        ema10 = ema10Values,
        ema60 = ema60Values,
        rsi12 = rsi12Values,
        rsi24 = rsi24Values,
        ma5 = ma5Values,
        ma10 = ma10Values,
        ma20 = ma20Values,
        ma60 = ma60Values,
        // 布林带
        bollUpper = bollUpperValues,
        bollMid = bollMidValues,
        bollLower = bollLowerValues
    )
}

/**
 * 将 Candle 列表转换为 CandleChartData（带 yield）
 *
 * 与 [toCandleChartData] 相同，但在每类指标计算之间插入 yield()，
 * 让 Compose 有机会提交中间帧，避免主线程一次性算完 500 根 K 线的
 * EMA×4 + MA×4 + RSI×3 + MACD + BOLL 时掉帧。
 */
suspend fun List<Candle>.toCandleChartDataYielding(code: String, name: String): CandleChartData {
    if (isEmpty()) {
        return CandleChartData(
            code = code, name = name,
            candles = emptyList(), volumes = emptyList(),
            ema20 = emptyList(), rsi6 = emptyList(),
            macdDif = emptyList(), macdDea = emptyList(), macdBar = emptyList()
        )
    }

    val candleDataList = mapIndexed { index, candle ->
        CandleData(
            date = candle.tradeTime ?: candle.date.toString(),
            open = candle.open, high = candle.high, low = candle.low, close = candle.close,
            volume = candle.volume, turnover = candle.turnoverReal,
            changePercent = if (index > 0) {
                val prev = get(index - 1)
                if (prev.close != 0f) ((candle.close - prev.close) / prev.close * 100) else null
            } else null
        )
    }

    val closes = map { it.close.toDouble() }
    val volumes = map { it.volume }

    // EMA
    val ema5Values = calculateEMAList(closes, 5)
    val ema10Values = calculateEMAList(closes, 10)
    val ema20Values = calculateEMAList(closes, 20)
    val ema60Values = calculateEMAList(closes, 60)
    yield()

    // MA
    val ma5Values = calculateSMAList(closes, 5)
    val ma10Values = calculateSMAList(closes, 10)
    val ma20Values = calculateSMAList(closes, 20)
    val ma60Values = calculateSMAList(closes, 60)
    yield()

    // RSI
    val rsi6Values = calculateRSIList(closes, 6)
    val rsi12Values = calculateRSIList(closes, 12)
    val rsi24Values = calculateRSIList(closes, 24)
    yield()

    // MACD
    val (macdDifValues, macdDeaValues, macdBarValues) = calculateMACDList(closes)
    yield()

    // 布林带
    val (bollUpperValues, bollMidValues, bollLowerValues) = calculateBollingerBandsList(closes, 20, 2.0)
    yield()

    return CandleChartData(
        code = code, name = name,
        candles = candleDataList, volumes = volumes,
        ema20 = ema20Values, rsi6 = rsi6Values,
        macdDif = macdDifValues, macdDea = macdDeaValues, macdBar = macdBarValues,
        ema5 = ema5Values, ema10 = ema10Values, ema60 = ema60Values,
        rsi12 = rsi12Values, rsi24 = rsi24Values,
        ma5 = ma5Values, ma10 = ma10Values, ma20 = ma20Values, ma60 = ma60Values,
        bollUpper = bollUpperValues, bollMid = bollMidValues, bollLower = bollLowerValues
    )
}

// ==================== 技术指标计算函数委托 ====================
// 所有指标计算实现在 IndicatorCalculator 对象，这里仅做语义化转发。

private fun calculateSMAList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateSMAList(data, period)

private fun calculateEMAList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateEMAList(data, period)

private fun calculateRSIList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateRSIList(data, period)

private fun calculateMACDList(
    data: List<Double>,
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): Triple<List<Float?>, List<Float?>, List<Float?>> =
    IndicatorCalculator.calculateMACDList(data, fastPeriod, slowPeriod, signalPeriod)

private fun calculateBollingerBandsList(
    data: List<Double>,
    period: Int = 20,
    multiplier: Double = 2.0
): Triple<List<Float?>, List<Float?>, List<Float?>> =
    IndicatorCalculator.calculateBollingerBandsList(data, period, multiplier)

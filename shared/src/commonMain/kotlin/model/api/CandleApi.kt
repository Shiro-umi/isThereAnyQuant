package model.api

import kotlin.uuid.ExperimentalUuidApi
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import model.Candle
import model.CandleData
import model.candle.CandlePeriod

/**
 * 蜡烛图数据查询响应
 * 对应后端 CandleResponse
 */
@Serializable
data class CandleResponse(
    val code: String,
    val name: String,
    val period: CandlePeriod,
    val adjusted: Boolean,
    val candles: List<CandleData>,
    val indicators: TechnicalIndicators? = null,
    val statistics: CandleStatistics
)

/**
 * 技术指标
 */
@Serializable
data class TechnicalIndicators(
    val ema: Map<String, List<Float?>>? = null,
    val ma: Map<String, List<Float?>>? = null,
    val rsi: Map<String, List<Float?>>? = null,
    val macd: MacdIndicators? = null,
    val boll: BollIndicators? = null
)

/**
 * MACD指标
 */
@Serializable
data class MacdIndicators(
    val dif: List<Float?>,
    val dea: List<Float?>,
    val bar: List<Float?>
)

/**
 * 布林带指标
 */
@Serializable
data class BollIndicators(
    val upper: List<Float?>,
    val mid: List<Float?>,
    val lower: List<Float?>
)

/**
 * 蜡烛图统计信息
 */
@Serializable
data class CandleStatistics(
    val count: Int,
    val startDate: String,
    val endDate: String,
    val highestPrice: Float,
    val lowestPrice: Float,
    val maxVolume: Float,
    val totalTurnover: Float,
    val priceChangePercent: Float
)

// ==================== 扩展函数 ====================

/**
 * 将 CandleData 转换为 Candle
 */
@OptIn(ExperimentalUuidApi::class)
fun CandleData.toCandle(tsCode: String): Candle {
    val hasTime = date.contains(' ')
    return Candle(
        tsCode = tsCode,
        date = LocalDate.parse(date.substringBefore(" ").take(10)),
        tradeTime = if (hasTime) date else null,
        open = open,
        high = high,
        low = low,
        close = close,
        adj = adjClose ?: close,
        openQfq = adjOpen ?: 0f,
        closeQfq = adjClose ?: 0f,
        highQfq = adjHigh ?: 0f,
        lowQfq = adjLow ?: 0f,
        volume = volume,
        volumeQfq = 0f,
        turnoverReal = turnover,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )
}

/**
 * 将 CandleResponse 转换为 Candle 列表
 */
fun CandleResponse.toCandleList(): List<Candle> {
    return candles.map { it.toCandle(code) }
}

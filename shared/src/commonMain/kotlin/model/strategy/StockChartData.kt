package model.strategy

import kotlinx.serialization.Serializable
import model.candle.CandleData

/**
 * 股票K线图表数据
 * 用于策略选股详情展示
 */
@Serializable
data class StockChartData(
    val code: String,
    val name: String,
    val candles: List<CandleData>,
    val ema20: List<Float?>,
    val rsi: List<Float?>,
    val volume: List<Float>,
    val tradeDate: String
)

/**
 * 股票图表数据响应
 */
@Serializable
data class StockChartResponse(
    val code: String,
    val name: String,
    val data: StockChartData?
)

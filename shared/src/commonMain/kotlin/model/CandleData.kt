package model

import kotlinx.serialization.Serializable

/**
 * 统一的K线数据模型
 * 用于前后端数据传输，支持复权价格
 */
@Serializable
data class CandleData(
    val date: String,                      // 日期 (yyyy-MM-dd)
    val open: Float,                       // 开盘价
    val high: Float,                       // 最高价
    val low: Float,                        // 最低价
    val close: Float,                      // 收盘价
    val volume: Float,                     // 成交量
    val turnover: Float,                   // 成交额
    val changePercent: Float? = null,      // 涨跌幅百分比
    val amplitude: Float? = null,          // 振幅百分比
    val adjOpen: Float? = null,            // 前复权开盘价
    val adjHigh: Float? = null,            // 前复权最高价
    val adjLow: Float? = null,             // 前复权最低价
    val adjClose: Float? = null            // 前复权收盘价
)

/**
 * K线数据列表响应
 */
@Serializable
data class CandleList(
    val code: String,                      // 股票代码
    val name: String,                      // 股票名称
    val candles: List<CandleData>,         // K线数据列表
    val totalCount: Int                    // 总数据条数
)

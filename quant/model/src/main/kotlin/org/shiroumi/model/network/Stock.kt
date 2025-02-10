package org.shiroumi.model.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.shiroumi.model.database.Candle
import kotlin.reflect.KClass


@Serializable
abstract class StockData(
    @SerialName("日期")
    val date: String,          // 使用 String 类型处理日期格式，可后续按需转换
    @SerialName("开盘")
    val open: Double,
    @SerialName("收盘")
    val close: Double,
    @SerialName("最高")
    val high: Double,
    @SerialName("最低")
    val low: Double,
    @SerialName("成交量")
    val volume: Double,          // Int64 对应 Kotlin Long
    @SerialName("成交额")
    val turnover: Double,
    @SerialName("振幅")
    val amplitude: Double,     // 百分比值，如 5.5 表示 5.5%
    @SerialName("涨跌幅")
    val changePercent: Double, // 百分比值
    @SerialName("涨跌额")
    val changeAmount: Double,
    @SerialName("换手率")
    val turnoverRate: Double   // 百分比值
) : ModelTypeBridge<Candle> {
    override val targetClass: KClass<Candle> = Candle::class
}

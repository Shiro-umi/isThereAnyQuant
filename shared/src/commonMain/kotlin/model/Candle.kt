package model

import kotlinx.serialization.Serializable

@Serializable
data class Candle(
    val date: String,
    val open: Float,
    val close: Float,
    val low: Float,
    val high: Float,
    val vol: Float,
)
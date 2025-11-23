package model.symbol

import kotlinx.serialization.Serializable
import model.Candle

@Serializable
data class Wave(
    val candle: Candle,
    val type: WaveType
)

@Serializable
sealed class WaveType {
    @Serializable
    data object High : WaveType()
    @Serializable
    data object Low : WaveType()
}

package model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Candle(
    val id: Uuid = Uuid.random(),
    val tsCode: String,
    val date: LocalDate,
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
)
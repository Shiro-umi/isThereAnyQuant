@file:OptIn(ExperimentalUuidApi::class)

package model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

typealias LLMTask = suspend (quant: Quant) -> Unit

fun Long.format(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val instant = Instant.fromEpochMilliseconds(this)
    val localDateTime = instant.toLocalDateTime(timeZone)
    return localDateTime.format(DateTimeFormatters.YYYY_MM_DD_HH_MM)
}

private object DateTimeFormatters {
    val YYYY_MM_DD_HH_MM = LocalDateTime.Format {
        year()
        char('/')
        monthNumber()
        char('/')
        dayOfMonth()
        char(' ')
        hour()
        char(':')
        minute()
    }
}

@Serializable
data class Quant(
    val uuid: Uuid = Uuid.random(),
    val code: String,
    val name: String,
    val status: Status = Status.Pending,
    val progress: Progress = Progress(),
    val targetDate: String = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString().replace("-", ""),
    val triggerTime: Long = Clock.System.now().toEpochMilliseconds(),
    @Serializable(LambdaSerializer::class)
    val tasks: List<LLMTask>? = null
)

object LambdaSerializer : KSerializer<List<LLMTask>> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LambdaAsDefault", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<LLMTask>) {
        encoder.encodeString("default")
    }

    override fun deserialize(decoder: Decoder) = listOf<suspend (quant: Quant) -> Unit>({})
}

@Serializable
data class Progress(
    val step: Int = 0,
    val totalStep: Int = 0,
    val description: String = "",
    val progress: Float = 0f,
)

@Serializable(with = StatusSerializer::class)
sealed class Status() {
    @Serializable
    object Pending : Status()

    @Serializable
    object Running : Status()

    @Serializable
    object Done : Status()

    @Serializable
    object Error : Status()
}

object StatusSerializer : KSerializer<Status> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StatusAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Status) {
        val string = when (value) {
            is Status.Pending -> "Pending"
            is Status.Running -> "Running"
            is Status.Done -> "Done"
            is Status.Error -> "Error"
        }
        encoder.encodeString(string)
    }

    override fun deserialize(decoder: Decoder): Status {
        return when (val string = decoder.decodeString()) {
            "Pending" -> Status.Pending
            "Running" -> Status.Running
            "Done" -> Status.Done
            "Error" -> Status.Error
            else -> throw IllegalArgumentException("Unknown status: $string")
        }
    }
}



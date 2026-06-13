package org.shiroumi.strategy.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

const val STRATEGY_CONTRACT_VERSION: Int = 1

@Serializable
enum class StrategyTopic {
    INTRADAY,
    POSITIONS,
    POSITION_TRACKING,
    HEALTH
}

@Serializable
data class StrategySnapshotEnvelope<T>(
    val topic: StrategyTopic,
    val version: Long,
    val sourceInstanceId: String,
    val publishedAt: Instant,
    val payload: T
)

@Serializable
data class StrategyWireSnapshot(
    val topic: StrategyTopic,
    val version: Long,
    val sourceInstanceId: String,
    val publishedAt: Instant,
    val contractVersion: Int = STRATEGY_CONTRACT_VERSION,
    val payload: JsonElement
)

@Serializable
sealed interface StrategySocketFrame {
    @Serializable
    @SerialName("subscribe")
    data class Subscribe(
        val requestId: String,
        val contractVersion: Int = STRATEGY_CONTRACT_VERSION,
        val topics: Set<StrategyTopic>
    ) : StrategySocketFrame

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val snapshot: StrategyWireSnapshot
    ) : StrategySocketFrame

    @Serializable
    @SerialName("command")
    data class Command(
        val requestId: String,
        val contractVersion: Int = STRATEGY_CONTRACT_VERSION,
        val command: StrategyCommand
    ) : StrategySocketFrame

    @Serializable
    @SerialName("ack")
    data class Ack(
        val requestId: String,
        val ack: StrategyCommandAck
    ) : StrategySocketFrame
}

interface StrategySnapshotSource<T> {
    suspend fun current(topic: StrategyTopic): StrategySnapshotEnvelope<T>?

    fun observe(topic: StrategyTopic): Flow<StrategySnapshotEnvelope<T>>
}

interface StrategySnapshotSink<T> {
    suspend fun publish(topic: StrategyTopic, payload: T): StrategySnapshotEnvelope<T>
}

@Serializable
sealed interface StrategyCommand {
    @Serializable
    @SerialName("health-check")
    data object HealthCheck : StrategyCommand

    @Serializable
    @SerialName("refresh-intraday")
    data class RefreshIntraday(val reason: String) : StrategyCommand

    @Serializable
    @SerialName("rebuild-date")
    data class RebuildDate(val tradeDate: String, val reason: String? = null) : StrategyCommand

    @Serializable
    @SerialName("rebuild-range")
    data class RebuildRange(val startDate: String, val endDate: String, val reason: String? = null) : StrategyCommand

    @Serializable
    @SerialName("load-realtime-daily-candles")
    data class LoadRealtimeDailyCandles(
        val tsCodes: List<String>,
        val tradeDate: String
    ) : StrategyCommand

    /**
     * 最早跟随日校准：以 [followStartDate]（ISO yyyy-MM-dd）为第一笔跟随买入日、
     * 空仓起步重放生产持仓规则，ack payload 返回跟随者视角的持仓跟踪快照
     * （`StrategyPositionTrackingResponse`）。
     */
    @Serializable
    @SerialName("build-calibrated-tracking")
    data class BuildCalibratedTracking(
        val followStartDate: String
    ) : StrategyCommand
}

@Serializable
data class StrategyCommandAck(
    val accepted: Boolean,
    val message: String? = null,
    val sourceInstanceId: String? = null,
    val contractVersion: Int = STRATEGY_CONTRACT_VERSION,
    val payload: JsonElement? = null
)

interface StrategyCommandClient {
    suspend fun send(command: StrategyCommand): StrategyCommandAck
}

interface StrategyRuntimeClient<T> : StrategySnapshotSource<T>, StrategyCommandClient

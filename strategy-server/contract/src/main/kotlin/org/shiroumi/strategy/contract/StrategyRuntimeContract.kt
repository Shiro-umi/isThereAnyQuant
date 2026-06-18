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

    /**
     * 强制重发布内存快照：不重算（不跑因子/情绪/模型选股/状态机推进），只从 DB 重读最新落库的
     * 审计与持仓，重新 publish POSITIONS / POSITION_TRACKING 快照。
     *
     * 用途：外部直接改库（重刷持仓、回填买点、离线脚本落库等）后，在跑的 service 内存快照仍是旧的，
     * 前端手动触发本命令即可让内存快照追平已落库状态，无需重启 service。与 [RebuildDate] 区分——
     * 后者会重算整条盘后链路（慢、且触发 selection 复现断言）；本命令是纯重读重发布。
     * 与 service 内部的「审计追平」（仅当 DB 审计日比内存新时刷新）区分——本命令无条件强制刷新，
     * 覆盖「审计日未变但库内数据已改」（如重刷同一交易日持仓口径）的场景。
     */
    @Serializable
    @SerialName("republish-latest-snapshot")
    data class RepublishLatestSnapshot(val reason: String = "manual-refresh") : StrategyCommand

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

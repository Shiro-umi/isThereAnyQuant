package org.shiroumi.server.runtime.strategy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.Candle
import model.candle.CandlePeriod
import model.candle.StrategyPositionTrackingResponse
import model.dataprovider.CandleKey
import model.ws.IntradaySnapshotPayload
import model.ws.StrategyPositionSnapshot
import org.shiroumi.config.ConfigManager
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.strategy.client.SocketStrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import utils.logger

/**
 * Ktor 端 strategy-service 单 owner 适配层。
 *
 * 仅负责通过 [SocketStrategyRuntimeClient] 消费 service 发布的 snapshot，
 * 以及向 service 转发指令。Ktor 不再在本地 publish 任何 SERVICE_OWNED_TOPICS。
 */
object StrategyRuntimeBridge {
    private val logger by logger("StrategyRuntimeBridge")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val remoteRuntimeClient = createRemoteRuntimeClient()

    suspend fun currentRemoteIntraday(): StrategySnapshotEnvelope<IntradaySnapshotPayload>? =
        remoteRuntimeClient?.current(StrategyTopic.INTRADAY)?.decodePayload(IntradaySnapshotPayload.serializer())

    suspend fun currentRemotePositions(): StrategySnapshotEnvelope<StrategyPositionSnapshot>? =
        remoteRuntimeClient?.current(StrategyTopic.POSITIONS)?.decodePayload(StrategyPositionSnapshot.serializer())

    fun observeRemoteIntraday(): Flow<StrategySnapshotEnvelope<IntradaySnapshotPayload>>? =
        remoteRuntimeClient?.observe(StrategyTopic.INTRADAY)
            ?.mapNotNull { it.decodePayload(IntradaySnapshotPayload.serializer()) }

    fun observeRemotePositions(): Flow<StrategySnapshotEnvelope<StrategyPositionSnapshot>>? =
        remoteRuntimeClient?.observe(StrategyTopic.POSITIONS)
            ?.mapNotNull { it.decodePayload(StrategyPositionSnapshot.serializer()) }

    suspend fun currentRemotePositionTracking(): StrategySnapshotEnvelope<StrategyPositionTrackingResponse>? =
        remoteRuntimeClient?.current(StrategyTopic.POSITION_TRACKING)
            ?.decodePayload(StrategyPositionTrackingResponse.serializer())

    fun observeRemotePositionTracking(): Flow<StrategySnapshotEnvelope<StrategyPositionTrackingResponse>>? =
        remoteRuntimeClient?.observe(StrategyTopic.POSITION_TRACKING)
            ?.mapNotNull { it.decodePayload(StrategyPositionTrackingResponse.serializer()) }

    suspend fun sendCommand(command: StrategyCommand): StrategyCommandAck =
        remoteRuntimeClient?.send(command)
            ?: StrategyCommandAck(
                accepted = false,
                message = "strategy-service runtime client is disabled",
                sourceInstanceId = "ktor-server"
            )

    /**
     * 最早跟随日校准：请求 strategy-service 以 [followStartDate] 空仓起步重放生产持仓规则，
     * 返回跟随者视角的持仓跟踪快照。失败时携带 service 侧的拒绝原因。
     */
    suspend fun buildCalibratedTracking(followStartDate: LocalDate): Result<StrategyPositionTrackingResponse> {
        // ack.message 经 StrategyPositionTrackingSubscriptionService 透传至前端展示，
        // 因此包装进 IllegalStateException 的兜底文案必须是面向用户的自然语言。
        val ack = sendCommand(StrategyCommand.BuildCalibratedTracking(followStartDate.toString()))
        if (!ack.accepted) {
            return Result.failure(IllegalStateException(ack.message ?: "该跟随起始日无法校准，请重新选择交易日"))
        }
        val payload = ack.payload
            ?: return Result.failure(IllegalStateException("该跟随起始日无法校准，请重新选择交易日"))
        return runCatching {
            json.decodeFromJsonElement(StrategyPositionTrackingResponse.serializer(), payload)
        }
    }

    suspend fun rebuildPostMarketDate(tradeDate: kotlinx.datetime.LocalDate, reason: String): StrategyCommandAck =
        sendCommand(
            StrategyCommand.RebuildDate(
                tradeDate = tradeDate.toString(),
                reason = reason
            )
        )

    private fun createRemoteRuntimeClient(): SocketStrategyRuntimeClient? {
        val enabled = System.getenv("STRATEGY_SOCKET_CONSUME_ENABLED")
            ?.equals("false", ignoreCase = true) != true
        if (!enabled) return null
        val host = strategyServiceHost()
        val port = strategyServicePort()
        val commandTimeoutMs = strategyCommandTimeoutMs()
        logger.info("strategy-service socket consumer enabled host=$host port=$port commandTimeoutMs=$commandTimeoutMs")
        return SocketStrategyRuntimeClient(
            host = host,
            port = port,
            commandTimeoutMillis = commandTimeoutMs,
            commandHandler = ::handleServiceCommand,
            topics = setOf(
                StrategyTopic.INTRADAY,
                StrategyTopic.POSITIONS,
                StrategyTopic.POSITION_TRACKING,
                StrategyTopic.HEALTH
            )
        )
    }

    private suspend fun handleServiceCommand(command: StrategyCommand): StrategyCommandAck = when (command) {
        is StrategyCommand.LoadRealtimeDailyCandles -> loadRealtimeDailyCandles(command)
        else -> StrategyCommandAck(
            accepted = false,
            message = "unsupported command for ktor-server: ${command::class.simpleName}",
            sourceInstanceId = "ktor-server"
        )
    }

    private fun loadRealtimeDailyCandles(command: StrategyCommand.LoadRealtimeDailyCandles): StrategyCommandAck {
        val tradeDate = runCatching { LocalDate.parse(command.tradeDate) }.getOrNull()
            ?: return StrategyCommandAck(
                accepted = false,
                message = "invalid tradeDate=${command.tradeDate}",
                sourceInstanceId = "ktor-server"
            )
        val candles = command.tsCodes
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { tsCode ->
                DataLayerBootstrap.candleFacade
                    .readMergedCandles(CandleKey(tsCode, CandlePeriod.DAY))
                    .lastOrNull { it.date == tradeDate }
            }
            .toList()
        return StrategyCommandAck(
            accepted = true,
            message = "loaded realtime daily candles=${candles.size}",
            sourceInstanceId = "ktor-server",
            payload = json.encodeToJsonElement(ListSerializer(Candle.serializer()), candles)
        )
    }

    private fun <T> StrategySnapshotEnvelope<JsonElement>.decodePayload(
        deserializer: kotlinx.serialization.DeserializationStrategy<T>
    ): StrategySnapshotEnvelope<T>? {
        return runCatching {
            StrategySnapshotEnvelope(
                topic = topic,
                version = version,
                sourceInstanceId = sourceInstanceId,
                publishedAt = publishedAt,
                payload = json.decodeFromJsonElement(deserializer, payload)
            )
        }.onFailure { error ->
            logger.warning("strategy-service snapshot decode failed topic=$topic: ${error.message}")
        }.getOrNull()
    }

    private fun strategyServiceHost(): String =
        System.getenv("STRATEGY_SOCKET_HOST")
            ?: runCatching { ConfigManager.getConfig().strategy.strategyServiceHost }.getOrNull()
            ?: "127.0.0.1"

    private fun strategyServicePort(): Int =
        System.getenv("STRATEGY_SOCKET_PORT")?.toIntOrNull()
            ?: runCatching { ConfigManager.getConfig().strategy.strategyServicePort }.getOrNull()
            ?: 9971

    private fun strategyCommandTimeoutMs(): Long =
        System.getenv("STRATEGY_COMMAND_TIMEOUT_MS")?.toLongOrNull()
            ?: 60_000
}

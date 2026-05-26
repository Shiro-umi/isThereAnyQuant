package org.shiroumi.strategy.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.STRATEGY_CONTRACT_VERSION
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.service.runtime.IntradayRuntime
import org.shiroumi.strategy.service.runtime.PostMarketRuntime
import utils.logger

/**
 * strategy-service command 分发处理器。
 *
 * 将 socket frame 中的 command 映射到具体 runtime 操作，
 * 并生成携带明确成功/失败原因的 ack。
 */
class StrategyCommandHandler(
    private val serviceInstanceId: String,
    private val intradayRuntime: IntradayRuntime,
    private val postMarketRuntime: PostMarketRuntime,
    private val snapshotHub: LocalStrategySnapshotHub<JsonElement>,
    private val json: Json,
) {
    private val logger by logger("StrategyCommandHandler")

    suspend fun handle(command: StrategyCommand): StrategyCommandAck {
        return when (command) {
            StrategyCommand.HealthCheck -> StrategyCommandAck(
                accepted = true,
                message = "strategy-service socket runtime available",
                sourceInstanceId = serviceInstanceId,
                contractVersion = STRATEGY_CONTRACT_VERSION,
            )

            is StrategyCommand.RefreshIntraday -> {
                val result = intradayRuntime.refresh(command.reason)
                publishHealth(
                    status = if (result.accepted) "INTRADAY_REFRESHED" else "INTRADAY_REFRESH_FAILED",
                    topic = StrategyTopic.INTRADAY,
                    version = result.intradayEnvelope?.version,
                    sourceInstanceId = result.intradayEnvelope?.sourceInstanceId,
                )
                StrategyCommandAck(
                    accepted = result.accepted,
                    message = result.message,
                    sourceInstanceId = serviceInstanceId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                )
            }

            is StrategyCommand.RebuildDate -> runCatching {
                val result = postMarketRuntime.rebuildDate(
                    tradeDate = kotlinx.datetime.LocalDate.parse(command.tradeDate),
                    reason = command.reason,
                )
                publishHealth(
                    status = if (result.accepted) "POST_MARKET_REBUILD_COMPLETED" else "POST_MARKET_REBUILD_FAILED",
                    topic = StrategyTopic.POSITIONS,
                    version = result.positionsEnvelope?.version,
                    sourceInstanceId = result.positionsEnvelope?.sourceInstanceId,
                )
                StrategyCommandAck(
                    accepted = result.accepted,
                    message = result.message,
                    sourceInstanceId = serviceInstanceId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                )
            }.getOrElse { error ->
                StrategyCommandAck(
                    accepted = false,
                    message = "invalid RebuildDate command: ${error.message}",
                    sourceInstanceId = serviceInstanceId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                )
            }

            is StrategyCommand.RebuildRange -> runCatching {
                val result = postMarketRuntime.rebuildRange(
                    startDate = kotlinx.datetime.LocalDate.parse(command.startDate),
                    endDate = kotlinx.datetime.LocalDate.parse(command.endDate),
                    reason = command.reason,
                )
                publishHealth(
                    status = if (result.accepted) "POST_MARKET_REBUILD_COMPLETED" else "POST_MARKET_REBUILD_FAILED",
                    topic = StrategyTopic.POSITIONS,
                    version = result.positionsEnvelope?.version,
                    sourceInstanceId = result.positionsEnvelope?.sourceInstanceId,
                )
                StrategyCommandAck(
                    accepted = result.accepted,
                    message = result.message,
                    sourceInstanceId = serviceInstanceId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                )
            }.getOrElse { error ->
                StrategyCommandAck(
                    accepted = false,
                    message = "invalid RebuildRange command: ${error.message}",
                    sourceInstanceId = serviceInstanceId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                )
            }

            is StrategyCommand.LoadRealtimeDailyCandles -> StrategyCommandAck(
                accepted = false,
                message = "LoadRealtimeDailyCandles must be handled by ktor-server",
                sourceInstanceId = serviceInstanceId,
                contractVersion = STRATEGY_CONTRACT_VERSION,
            )
        }
    }

    private suspend fun publishHealth(
        status: String,
        topic: StrategyTopic? = null,
        version: Long? = null,
        sourceInstanceId: String? = null,
    ) {
        snapshotHub.publish(
            StrategyTopic.HEALTH,
            buildJsonObject {
                put("status", status)
                put("serviceInstanceId", serviceInstanceId)
                put("contractVersion", STRATEGY_CONTRACT_VERSION)
                topic?.let { put("lastTopic", it.name) }
                version?.let { put("lastVersion", it) }
                sourceInstanceId?.let { put("lastSourceInstanceId", it) }
                put("updatedAtEpochMillis", System.currentTimeMillis())
            },
        )
    }
}

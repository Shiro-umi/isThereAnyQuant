package org.shiroumi.server.subscription.strategy

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.server.runtime.strategy.StrategyPositionHolder
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge
import org.shiroumi.server.runtime.strategy.StrategySnapshotCursor
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * `STRATEGY_POSITIONS` 响应式订阅服务。
 *
 * Ktor 不再产生任何 `POSITIONS` snapshot：本服务只是 service-first 的 adapter。
 * - service 可达时立即 `SYNC` 当前快照，并通过 remote flow 推送后续 `UPDATE`
 * - service 不可达时直接发 `ERROR` 帧，不再回退到本地 Holder snapshot
 * - 收到的 service snapshot 通过 [StrategyPositionHolder.updateFromService] 同步本地 last-known
 */
class StrategyPositionSubscriptionService(
    private val positionHolder: StrategyPositionHolder,
) {
    private val logger by logger("StrategyPositionSubscriptionService")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscriptionJobs = ConcurrentHashMap<DefaultWebSocketServerSession, Job>()

    /**
     * 建立订阅。
     *
     * - 立即推送 SYNC（如有 currentRemote）
     * - 后续 collectLatest 推送 UPDATE
     * - service 不可达时发 ERROR 帧并 return
     */
    suspend fun subscribe(session: DefaultWebSocketServerSession) {
        subscriptionJobs.remove(session)?.cancel()

        val remoteCurrent = StrategyRuntimeBridge.currentRemotePositions()
        val remoteFlow = StrategyRuntimeBridge.observeRemotePositions()

        if (remoteCurrent == null && remoteFlow == null) {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITIONS,
                    action = WsAction.ERROR,
                    payload = "strategy-service 策略持仓 snapshot 当前不可用"
                )
            )
            logger.warning("[STRATEGY_POSITIONS] remote snapshot unavailable")
            return
        }

        remoteCurrent?.let {
            positionHolder.updateFromService(it.payload, reason = "strategy-service-initial")
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITIONS,
                    action = WsAction.SYNC,
                    payload = json.encodeToString(it.payload)
                )
            )
        }

        remoteFlow?.let { flow ->
            val cursor = StrategySnapshotCursor(
                sourceInstanceId = remoteCurrent?.sourceInstanceId,
                version = remoteCurrent?.version ?: Long.MIN_VALUE
            )
            var sentInitial = remoteCurrent != null
            subscriptionJobs[session] = scope.launch {
                flow.buffer(Channel.CONFLATED).collectLatest { snapshot ->
                    if (!cursor.shouldAccept(snapshot)) return@collectLatest
                    positionHolder.updateFromService(snapshot.payload, reason = "strategy-service-update")
                    AppWebSocketConnectionManager.sendToSession(
                        session,
                        WsEvent(
                            topic = WsTopic.STRATEGY_POSITIONS,
                            action = if (sentInitial) WsAction.UPDATE else WsAction.SYNC,
                            payload = json.encodeToString(snapshot.payload)
                        )
                    )
                    sentInitial = true
                }
            }
        }

        logger.info(
            "[STRATEGY_POSITIONS] SUBSCRIBED session=${session.hashCode()}, dataReady=${remoteCurrent != null}"
        )
    }

    /**
     * 取消订阅。
     */
    fun unsubscribe(session: DefaultWebSocketServerSession) {
        val removed = subscriptionJobs.remove(session)
        removed?.cancel()
        if (removed != null) {
            logger.info("[STRATEGY_POSITIONS] UNSUBSCRIBED session=${session.hashCode()}")
        }
    }

    /**
     * 会话断开时的清理入口。
     */
    fun cleanupSession(session: DefaultWebSocketServerSession) {
        unsubscribe(session)
    }

    fun shutdown() {
        scope.cancel()
    }
}

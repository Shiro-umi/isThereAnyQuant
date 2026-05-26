package org.shiroumi.server.subscription.intraday

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
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge
import org.shiroumi.server.runtime.strategy.StrategySnapshotCursor
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * `INTRADAY_SNAPSHOT` 订阅服务。
 *
 * Ktor 不再产生任何 `INTRADAY` snapshot：本服务只是 service-first 的 adapter。
 * - service 可达时立即 `SYNC` 当前快照，并通过 remote flow 推送后续 `UPDATE`
 * - service 不可达时直接发送 `ERROR` 帧，不再回退到本地 Provider/Projection
 */
class IntradaySnapshotSubscriptionService {
    private val logger by logger("IntradaySnapshotSubscriptionService")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscriptionJobs = ConcurrentHashMap<DefaultWebSocketServerSession, Job>()

    /**
     * 建立盘中快照订阅。
     *
     * - 优先 `currentRemoteIntraday()` 立即推 `SYNC`
     * - 后续 `observeRemoteIntraday()` 推 `UPDATE`
     * - service 不可达时发送 `ERROR` 帧并 return
     */
    suspend fun subscribe(session: DefaultWebSocketServerSession) {
        val remoteCurrent = StrategyRuntimeBridge.currentRemoteIntraday()
        val remoteFlow = StrategyRuntimeBridge.observeRemoteIntraday()
        if (remoteCurrent == null && remoteFlow == null) {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.INTRADAY_SNAPSHOT,
                    action = WsAction.ERROR,
                    payload = "strategy-service 盘中 snapshot 当前不可用"
                )
            )
            logger.warning("[INTRADAY_SNAPSHOT] remote snapshot unavailable")
            return
        }

        remoteCurrent?.let {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.INTRADAY_SNAPSHOT,
                    action = WsAction.SYNC,
                    payload = json.encodeToString(it.payload)
                )
            )
        }

        subscriptionJobs.remove(session)?.cancel()
        remoteFlow?.let { flow ->
            val cursor = StrategySnapshotCursor(
                sourceInstanceId = remoteCurrent?.sourceInstanceId,
                version = remoteCurrent?.version ?: Long.MIN_VALUE
            )
            var sentInitial = remoteCurrent != null
            subscriptionJobs[session] = scope.launch {
                flow.buffer(Channel.CONFLATED).collectLatest { snapshot ->
                    if (!cursor.shouldAccept(snapshot)) return@collectLatest
                    AppWebSocketConnectionManager.sendToSession(
                        session,
                        WsEvent(
                            topic = WsTopic.INTRADAY_SNAPSHOT,
                            action = if (sentInitial) WsAction.UPDATE else WsAction.SYNC,
                            payload = json.encodeToString(snapshot.payload)
                        )
                    )
                    sentInitial = true
                }
            }
        }
        logger.info("[strategy-service快照订阅] SUBSCRIBE node=${WsTopic.INTRADAY_SNAPSHOT}")
    }

    /**
     * 取消当前会话的盘中快照订阅。
     */
    fun unsubscribe(session: DefaultWebSocketServerSession) {
        val removed = subscriptionJobs.remove(session)
        removed?.cancel()
        if (removed != null) {
            logger.info("[strategy-service快照订阅] UNSUBSCRIBE node=${WsTopic.INTRADAY_SNAPSHOT}")
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

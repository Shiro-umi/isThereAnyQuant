package org.shiroumi.server.subscription.strategy

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.candle.StrategyPositionTrackingResponse
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.database.user.createUserRepository
import org.shiroumi.database.user.repository.UserRepository
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge
import org.shiroumi.server.runtime.strategy.StrategySnapshotCursor
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import utils.logger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * `STRATEGY_POSITION_TRACKING` 响应式订阅服务。
 *
 * Ktor 不再产生任何 `POSITION_TRACKING` snapshot：本服务只是 service-first 的 adapter。
 * - service 可达时立即 `SYNC` 当前快照，并通过 remote flow 推送后续 `UPDATE`
 * - service 不可达时直接发 `ERROR` 帧，不再回退到本地 SnapshotService
 * - 若用户设置了 `tracking_follow_start_date`，则推送策略服务校准后的跟随者视角快照
 */
class StrategyPositionTrackingSubscriptionService {
    private val logger by logger("StrategyPositionTrackingSubscriptionService")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscriptionJobs = ConcurrentHashMap<DefaultWebSocketServerSession, Job>()
    private val sessionFollowStartDates: MutableMap<DefaultWebSocketServerSession, String?> =
        Collections.synchronizedMap(mutableMapOf())
    private val userRepository: UserRepository by lazy { createUserRepository() }

    private val cacheMutex = Mutex()

    /**
     * 校准结果 LRU 缓存。
     *
     * Key = (followStartDate, modelSnapshotVersion)，Value = 校准后的持仓跟踪快照。
     * 使用 snapshot 的 version 作为缓存版本，避免重复请求 strategy-service。
     */
    private val calibratedCache = object : LinkedHashMap<Pair<String, Long>, StrategyPositionTrackingResponse>(
        16,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, StrategyPositionTrackingResponse>?): Boolean {
            return size > 50
        }
    }

    /**
     * 建立订阅。
     *
     * - 立即推送 SYNC（如有 currentRemote），并根据用户校准设置推送校准视图
     * - 后续 collectLatest 推送 UPDATE，同样应用用户校准
     * - service 不可达时发 ERROR 帧并 return
     */
    suspend fun subscribe(session: DefaultWebSocketServerSession) {
        subscriptionJobs.remove(session)?.cancel()

        val remoteCurrent = StrategyRuntimeBridge.currentRemotePositionTracking()
        val remoteFlow = StrategyRuntimeBridge.observeRemotePositionTracking()

        if (remoteCurrent == null && remoteFlow == null) {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITION_TRACKING,
                    action = WsAction.ERROR,
                    payload = "策略数据暂时无法获取，请稍后重试"
                )
            )
            logger.warning("[STRATEGY_POSITION_TRACKING] remote snapshot unavailable")
            return
        }

        if (remoteCurrent == null) {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITION_TRACKING,
                    action = WsAction.ERROR,
                    payload = "策略数据正在初始化，请稍后重试"
                )
            )
        }

        val userId = AppWebSocketConnectionManager.getUserId(session)
        sessionFollowStartDates[session] = userId?.let { userRepository.getTrackingFollowStartDate(it) }

        remoteCurrent?.let {
            pushTracking(session, WsAction.SYNC, it)
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
                    pushTracking(
                        session,
                        if (sentInitial) WsAction.UPDATE else WsAction.SYNC,
                        snapshot
                    )
                    sentInitial = true
                }
            }
        }

        logger.info(
            "[STRATEGY_POSITION_TRACKING] SUBSCRIBED session=${session.hashCode()}, " +
                "dataReady=${remoteCurrent != null}"
        )
    }

    /**
     * 重新推送当前远程快照，应用用户最新的校准设置。
     *
     * 当用户通过 `SET_TRACKING_FOLLOW_START_DATE` 修改最早跟随日时调用。
     */
    suspend fun refresh(session: DefaultWebSocketServerSession) {
        val userId = AppWebSocketConnectionManager.getUserId(session)
        sessionFollowStartDates[session] = userId?.let { userRepository.getTrackingFollowStartDate(it) }

        val remoteCurrent = StrategyRuntimeBridge.currentRemotePositionTracking()
        if (remoteCurrent == null) {
            // service 尚未就绪时若静默返回，前端校准请求会永久挂起在 loading；
            // 明确发 ERROR 帧，让客户端退出 loading 并展示原因。
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITION_TRACKING,
                    action = WsAction.ERROR,
                    payload = "策略数据正在初始化，校准暂不可用，请稍后重试"
                )
            )
            logger.warning("[STRATEGY_POSITION_TRACKING] refresh skipped: remote snapshot unavailable")
            return
        }
        pushTracking(session, WsAction.SYNC, remoteCurrent)
    }

    /**
     * 取消订阅。
     */
    fun unsubscribe(session: DefaultWebSocketServerSession) {
        val removed = subscriptionJobs.remove(session)
        removed?.cancel()
        sessionFollowStartDates.remove(session)
        if (removed != null) {
            logger.info("[STRATEGY_POSITION_TRACKING] UNSUBSCRIBED session=${session.hashCode()}")
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

    /**
     * 向指定会话推送持仓跟踪快照，按用户设置的 `tracking_follow_start_date` 自动应用校准。
     *
     * - 未设置或为空：推送原始模型快照
     * - 已设置且校准成功：推送校准视图
     * - 已设置但校准失败（如日期超出窗口）：发送 ERROR 帧后回退到原始模型快照
     */
    private suspend fun pushTracking(
        session: DefaultWebSocketServerSession,
        action: WsAction,
        snapshot: StrategySnapshotEnvelope<StrategyPositionTrackingResponse>
    ) {
        val followStartDate = sessionFollowStartDates[session]

        val calibratedResult = if (!followStartDate.isNullOrBlank()) {
            buildCalibratedTrackingResult(followStartDate, snapshot.version)
        } else null

        if (calibratedResult is CalibratedResult.Failure) {
            AppWebSocketConnectionManager.sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.STRATEGY_POSITION_TRACKING,
                    action = WsAction.ERROR,
                    payload = calibratedResult.message
                )
            )
        }

        val effectivePayload = (calibratedResult as? CalibratedResult.Success)?.response ?: snapshot.payload
        AppWebSocketConnectionManager.sendToSession(
            session,
            WsEvent(
                topic = WsTopic.STRATEGY_POSITION_TRACKING,
                action = action,
                payload = json.encodeToString(effectivePayload)
            )
        )
    }

    private sealed class CalibratedResult {
        data class Success(val response: StrategyPositionTrackingResponse) : CalibratedResult()
        data class Failure(val message: String) : CalibratedResult()
    }

    /**
     * 获取校准后的持仓跟踪快照，使用 LRU 缓存避免重复调用 strategy-service。
     */
    private suspend fun buildCalibratedTrackingResult(
        followStartDate: String,
        modelVersion: Long
    ): CalibratedResult {
        val cacheKey = followStartDate to modelVersion
        cacheMutex.withLock { calibratedCache[cacheKey] }?.let {
            return CalibratedResult.Success(it)
        }

        return try {
            val response = StrategyRuntimeBridge.buildCalibratedTracking(LocalDate.parse(followStartDate)).getOrThrow()
            cacheMutex.withLock { calibratedCache[cacheKey] = response }
            CalibratedResult.Success(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("[STRATEGY_POSITION_TRACKING] calibrated tracking failed for $followStartDate: ${e.message}")
            // 仅透传 service 侧的业务拒绝原因（IllegalStateException 携带自然语言 message）；
            // 其它异常（如 snapshot 反序列化失败）携带技术细节，统一兜底为面向用户的提示。
            val userMessage = (e as? IllegalStateException)?.message ?: "该跟随起始日无法校准，请重新选择交易日"
            CalibratedResult.Failure(userMessage)
        }
    }
}

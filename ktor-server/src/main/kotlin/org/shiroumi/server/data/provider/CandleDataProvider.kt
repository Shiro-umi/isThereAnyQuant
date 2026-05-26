package org.shiroumi.server.data.provider

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.dataprovider.CandleKey
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.server.data.facade.CandleDataFacade
import org.shiroumi.server.data.snapshot.CandleSnapshotState
import org.shiroumi.server.data.subscription.CandleProjectionService
import org.shiroumi.server.data.trace.CandleTrace
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 新数据层下的 Candle topic provider。
 *
 * 这个 provider 明确只做三件事：
 * 1. 维护 `CANDLE_DATA` 的订阅关系
 * 2. 读取 facade 暴露的 snapshot 结果
 * 3. 把新版本投影成 payload 并推给订阅者
 *
 * 它刻意不负责：
 * - 任何缓存
 * - 任何外部接口调用
 * - 任何按 key 动态创建/释放实例
 *
 * 这也是本次重构最关键的职责分离点之一：provider 只是一层 topic mapper。
 */
class CandleDataProvider(
    private val facade: CandleDataFacade,
    private val projectionService: CandleProjectionService
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val subscribers =
        ConcurrentHashMap<CandleKey, MutableSet<DefaultWebSocketServerSession>>()
    private val subscriptionRequests =
        ConcurrentHashMap<Pair<DefaultWebSocketServerSession, CandleKey>, model.ws.CandleSubscribeRequest>()
    private val lastSentVersion = ConcurrentHashMap<CandleKey, Long>()

    // --- Payload encode cache ---
    // 缓存 (CandleKey, version, requestSignature) → 已编码的 K 线主体（candles 数组）JSON 片段。
    //
    // 设计要点：
    // - 只缓存与请求形态相关、占 payload 99% 体积的 K 线主体序列化结果，避免对 500 根 K 线
    //   反复 project + encodeToString；release 高并发同股观看时这一层最划算。
    // - 不缓存完整 payload。`requestParams.requestSeq` 是请求级瞬时标识，
    //   每次响应必须回带"本次"请求的 seq；与"快照级"复用缓存的语义不兼容。
    //   完整 payload 由 sendSnapshot 现场拼装。
    private data class EncodedCandles(val version: Long, val totalCount: Int, val candlesJson: String)
    private val encodedCache = ConcurrentHashMap<String, EncodedCandles>()

    private fun model.ws.CandleSubscribeRequest.signature(): String =
        "l=$limit:s=$startDate:e=$endDate:adj=$useAdjusted"

    private fun encodeCacheKey(key: CandleKey, request: model.ws.CandleSubscribeRequest): String =
        "${key.id}:${request.signature()}"

    suspend fun subscribe(
        session: DefaultWebSocketServerSession,
        key: CandleKey,
        request: model.ws.CandleSubscribeRequest
    ) {
        val startNanos = System.nanoTime()
        // 业务不变量：同一 session 同一周期同时只看一只股票（CandleViewModel.selectStock/
        // selectPeriod 切股切周期总是先 UNSUBSCRIBE 再 SUBSCRIBE）。由 server 自己守住这个
        // 不变量，可以让 SUBSCRIBE/UNSUBSCRIBE_CANDLE 共用同一个收敛域（candle:<period>），
        // 旧 tsCode 的命令在 channel 里被 stale 丢弃也不会留下泄漏订阅。
        releaseOtherTsCodeSubscriptions(session, key)
        subscribers.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(session)
        subscriptionRequests[session to key] = request
        CandleTrace.log(
            stage = "PROVIDER_REACHED",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = request.requestSeq,
            detail = "subscriberCount=${subscribers[key]?.size ?: 0}, session=${session.hashCode()}"
        )
        facade.readSnapshot(key, request.requestSeq)?.let { snapshot ->
            CandleTrace.log(
                stage = "PROVIDER_SNAPSHOT_HIT",
                tsCode = key.tsCode,
                period = key.period,
                requestSeq = request.requestSeq,
                detail = "version=${snapshot.version}, candles=${snapshot.candles.size}, elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000.0}"
            )
            sendSnapshot(session, key, request, snapshot, WsAction.SYNC)
            lastSentVersion[key] = maxOf(lastSentVersion[key] ?: 0L, snapshot.version)
        } ?: CandleTrace.log(
            stage = "PROVIDER_SNAPSHOT_MISS",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = request.requestSeq,
            detail = "elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000.0}"
        )
        CandleTrace.log(
            stage = "PROVIDER_SUBSCRIBE_DONE",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = request.requestSeq,
            detail = "elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000.0}"
        )
    }

    fun unsubscribe(session: DefaultWebSocketServerSession, key: CandleKey) {
        subscribers[key]?.remove(session)
        if (subscribers[key].isNullOrEmpty()) {
            subscribers.remove(key)
            lastSentVersion.remove(key)
        }
        subscriptionRequests.remove(session to key)
    }

    private fun releaseOtherTsCodeSubscriptions(
        session: DefaultWebSocketServerSession,
        targetKey: CandleKey
    ) {
        val obsoleteKeys = subscriptionRequests.keys
            .asSequence()
            .filter { (registeredSession, registeredKey) ->
                registeredSession == session &&
                    registeredKey.period == targetKey.period &&
                    registeredKey != targetKey
            }
            .map { it.second }
            .toList()
        obsoleteKeys.forEach { unsubscribe(session, it) }
    }

    fun cleanupSession(session: DefaultWebSocketServerSession) {
        val keys = subscriptionRequests.keys
            .filter { (registeredSession, _) -> registeredSession == session }
            .map { it.second }
            .distinct()
        keys.forEach { key ->
            unsubscribe(session, key)
        }
    }

    fun hasAnySubscribers(key: CandleKey): Boolean = subscribers[key].isNullOrEmpty().not()

    /**
     * `SyncLooper` 每秒广播一次有版本变化的读取机会。
     *
     * provider 不主动向 snapshot 注册回调，而是在这个固定节奏上消费 snapshot 的变更 key。
     * 这样做的原因不是“技术上更简单”，而是为了让：
     * - DAY 的 `rt_k` 批次推进
     * - minute miss -> 回填
     * - provider 推送
     *
     * 保持在同一个节奏面上，便于排障与限频分析。
     */
    suspend fun onDataSync(changedKeys: Collection<CandleKey>) {
        if (changedKeys.isEmpty()) return
        changedKeys.forEach { key ->
            val sessions = subscribers[key] ?: return@forEach
            if (sessions.isEmpty()) return@forEach
            // trace 只需要一个能够代表当前 key 的 requestSeq。
            // 这里直接复用任意一个已订阅 session 的请求，避免为了日志在热路径上全表扫描。
            val sampleRequest = sessions.firstOrNull()?.let { sampleSession ->
                subscriptionRequests[sampleSession to key]
            }
            val snapshot = facade.readSnapshot(key, sampleRequest?.requestSeq) ?: return@forEach
            val previousVersion = lastSentVersion[key]
            val action = if (previousVersion == null) WsAction.SYNC else WsAction.UPDATE
            if (previousVersion != null && snapshot.version <= previousVersion) {
                return@forEach
            }
            sessions.forEach { session ->
                val request = subscriptionRequests[session to key] ?: return@forEach
                sendSnapshot(session, key, request, snapshot, action)
            }
            lastSentVersion[key] = snapshot.version
        }
    }

    private suspend fun sendSnapshot(
        session: DefaultWebSocketServerSession,
        key: CandleKey,
        request: model.ws.CandleSubscribeRequest,
        snapshot: CandleSnapshotState,
        action: WsAction
    ) {
        val totalStartNanos = System.nanoTime()
        val ck = encodeCacheKey(key, request)
        val cached = encodedCache[ck]
        val candlesJson: String
        val totalCount: Int
        if (cached != null && cached.version == snapshot.version) {
            candlesJson = cached.candlesJson
            totalCount = cached.totalCount
            CandleTrace.log(
                stage = "PROVIDER_PAYLOAD_CACHED",
                tsCode = key.tsCode,
                period = key.period,
                requestSeq = request.requestSeq,
                detail = "action=$action, version=${snapshot.version}, candles=$totalCount"
            )
        } else {
            val projectionStartNanos = System.nanoTime()
            val payload = projectionService.project(key, request, snapshot)
            val projectionElapsedMs = (System.nanoTime() - projectionStartNanos) / 1_000_000.0
            CandleTrace.log(
                stage = "PROVIDER_PAYLOAD_READY",
                tsCode = key.tsCode,
                period = key.period,
                requestSeq = request.requestSeq,
                detail = "action=$action, version=${snapshot.version}, candles=${payload.totalCount}, projectionMs=$projectionElapsedMs"
            )
            candlesJson = json.encodeToString(payload.candles)
            totalCount = payload.totalCount
            encodedCache[ck] = EncodedCandles(snapshot.version, totalCount, candlesJson)
        }
        val encodedPayload = composePayloadJson(key.tsCode, candlesJson, totalCount, request)
        val event = WsEvent(
            topic = WsTopic.CANDLE_DATA,
            action = action,
            targetId = "${key.tsCode}:${key.period.name}",
            payload = encodedPayload
        )
        val sendStartNanos = System.nanoTime()
        AppWebSocketConnectionManager.sendToSession(session, event)
        CandleTrace.log(
            stage = "PROVIDER_SEND_COMPLETE",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = request.requestSeq,
            detail = "action=$action, version=${snapshot.version}, sendMs=${(System.nanoTime() - sendStartNanos) / 1_000_000.0}, totalMs=${(System.nanoTime() - totalStartNanos) / 1_000_000.0}"
        )
        CandleTrace.log(
            stage = "PROVIDER_SEND_SUMMARY",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = request.requestSeq,
            detail = "action=$action, version=${snapshot.version}, count=$totalCount"
        )
    }

    /**
     * 把"快照级可复用"的 candles JSON 与"请求级瞬时"的 requestParams 拼成完整 payload。
     *
     * 这样切分是因为 `requestSeq` 是每次订阅独立的瞬时标识，前端做严格相等校验来对齐请求/响应；
     * 如果把含 requestSeq 的整段 payload 缓存下来，下次同 key 命中时回带的将是过期的 requestSeq，
     * 导致前端丢弃响应而 K 线一直 loading。
     */
    private fun composePayloadJson(
        tsCode: String,
        candlesJson: String,
        totalCount: Int,
        request: model.ws.CandleSubscribeRequest
    ): String {
        val requestParamsJson = json.encodeToString(request)
        return buildString {
            append('{')
            append("\"tsCode\":").append(json.encodeToString(tsCode)).append(',')
            append("\"candles\":").append(candlesJson).append(',')
            append("\"totalCount\":").append(totalCount).append(',')
            append("\"requestParams\":").append(requestParamsJson)
            append('}')
        }
    }
}

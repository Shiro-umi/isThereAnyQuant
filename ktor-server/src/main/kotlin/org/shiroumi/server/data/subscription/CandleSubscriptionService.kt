package org.shiroumi.server.data.subscription

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.CandleErrorCode
import model.ws.CandleErrorPayload
import model.ws.CandleSubscribeRequest
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.server.data.provider.CandleDataProvider
import org.shiroumi.server.data.trace.CandleTrace
import org.shiroumi.server.websocket.AppWebSocketConnectionManager

/**
 * 新数据层下的 Candle 订阅服务。
 *
 * 它的职责是把 websocket 请求翻译为 provider 的订阅关系。
 * 与旧实现最大的区别是：
 * - 不再负责 provider 激活
 * - 不再负责 key 级轮询任务
 * - 不再区分 DAY 与非 DAY 的两条权威源
 */
class CandleSubscriptionService(
    private val provider: CandleDataProvider
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val supportedPeriods = setOf(
        CandlePeriod.DAY,
        CandlePeriod.WEEK,
        CandlePeriod.MONTH,
        CandlePeriod.MIN_5,
        CandlePeriod.MIN_15,
        CandlePeriod.MIN_30,
        CandlePeriod.MIN_60
    )

    suspend fun subscribe(
        session: DefaultWebSocketServerSession,
        tsCode: String,
        payloadJson: String?
    ) {
        val startNanos = System.nanoTime()
        val request = resolveRequest(tsCode, payloadJson)
        CandleTrace.log(
            stage = "SUBSCRIPTION_SERVICE_ENTER",
            tsCode = request.tsCode,
            period = request.period,
            requestSeq = request.requestSeq,
            detail = "limit=${request.limit}, adjusted=${request.useAdjusted}, session=${session.hashCode()}"
        )
        provider.subscribe(session, CandleKey(request.tsCode, request.period), request)
        CandleTrace.log(
            stage = "SUBSCRIPTION_SERVICE_EXIT",
            tsCode = request.tsCode,
            period = request.period,
            requestSeq = request.requestSeq,
            detail = "elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000.0}"
        )
    }

    suspend fun unsubscribe(
        session: DefaultWebSocketServerSession,
        tsCode: String,
        payloadJson: String? = null
    ) {
        val request = resolveRequest(tsCode, payloadJson)
        provider.unsubscribe(session, CandleKey(request.tsCode, request.period))
    }

    suspend fun cleanupSession(session: DefaultWebSocketServerSession) {
        provider.cleanupSession(session)
    }

    fun hasAnySubscribers(key: CandleKey): Boolean = provider.hasAnySubscribers(key)

    suspend fun sendSubscriptionError(
        session: DefaultWebSocketServerSession,
        tsCode: String,
        payloadJson: String?,
        errorCode: CandleErrorCode,
        message: String
    ) {
        val request = resolveRequest(tsCode, payloadJson)
        val event = WsEvent(
            topic = WsTopic.CANDLE_DATA,
            action = WsAction.ERROR,
            targetId = "${request.tsCode}:${request.period.name}",
            payload = json.encodeToString(
                CandleErrorPayload(
                    tsCode = request.tsCode,
                    errorCode = errorCode,
                    message = message,
                    requestSeq = request.requestSeq
                )
            )
        )
        AppWebSocketConnectionManager.sendToSession(session, event)
    }

    fun resolveRequest(defaultTsCode: String, payloadJson: String?): CandleSubscribeRequest {
        val request = parseRequest(payloadJson, defaultTsCode)
        val normalizedCode = normalizeStockCode(request.tsCode)
        require(request.period in supportedPeriods) {
            "新数据层不支持该周期: ${request.period}"
        }
        return request.copy(tsCode = normalizedCode)
    }

    fun resolveKey(defaultTsCode: String, payloadJson: String?): CandleKey {
        val request = resolveRequest(defaultTsCode, payloadJson)
        return CandleKey(request.tsCode, request.period)
    }

    private fun parseRequest(payloadJson: String?, defaultTsCode: String): CandleSubscribeRequest {
        if (payloadJson.isNullOrBlank()) {
            return defaultRequest(defaultTsCode)
        }
        return runCatching {
            json.decodeFromString<CandleSubscribeRequest>(payloadJson)
        }.getOrElse {
            defaultRequest(defaultTsCode)
        }
    }

    private fun defaultRequest(tsCode: String): CandleSubscribeRequest =
        CandleSubscribeRequest(
            tsCode = tsCode,
            period = CandlePeriod.DAY,
            limit = 500,
            useAdjusted = true
        )

    private fun normalizeStockCode(code: String): String {
        if (code.contains(".")) return code
        val paddedCode = code.padStart(6, '0')
        return when {
            paddedCode.startsWith("6") -> "$paddedCode.SH"
            paddedCode.startsWith("0") || paddedCode.startsWith("3") -> "$paddedCode.SZ"
            paddedCode.startsWith("8") || paddedCode.startsWith("4") -> "$paddedCode.BJ"
            else -> "$paddedCode.SZ"
        }
    }
}

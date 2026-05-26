package org.shiroumi.server.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.CandleSubscribeRequest
import org.shiroumi.database.agent.repository.AgentAnalysisResultRepository
import org.shiroumi.database.agent.repository.AgentShareKlineAllowlistRepository
import org.shiroumi.database.agent.repository.AgentShareViewLogRepository
import org.shiroumi.server.dataprovider.bootstrap.DataProviderBootstrap
import org.shiroumi.server.dto.ApiResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 匿名分享页和 K 线接口。
 *
 * 这两个路由必须挂在 `routing { }` 顶层（绕开 `authenticate("auth-jwt")`），
 * 由 `Application.ktorRouting()` 负责装配。
 *
 * 安全模型：
 * - shareToken 是不可枚举的 22 位 base62
 * - K 线接口请求参数必须与 (shareToken, blockKey) 白名单完全一致，
 *   否则 403；防止匿名用户用合法 token 探测任意股票/任意区间
 * - 简单 IP 限流：每 IP 60 次/分钟
 * - 主页访问异步写入 view_log，K 线接口不计入
 */
fun Route.publicShareRoutes() {
    // 主页：GET /s/{token}
    get("/s/{token}") {
        val token = call.parameters["token"]
        if (token.isNullOrBlank() || !isPlausibleToken(token)) {
            return@get call.respondText(
                text = renderNotFound("链接无效"),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.NotFound,
            )
        }

        val record = AgentAnalysisResultRepository.findByShareToken(token)
        if (record == null || record.shareToken != token) {
            return@get call.respondText(
                text = renderNotFound("分享不存在"),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.NotFound,
            )
        }

        // 异步记录访问，不阻塞响应
        val ip = call.clientIp()
        val ua = call.request.header("User-Agent")
        ShareViewLogger.recordAsync(token, ip, ua)

        val html = ShareHtmlRenderer.render(
            shareToken = token,
            contentMd = record.contentMd,
            tradeDate = record.tradeDate?.toString(),
            themeName = record.shareTheme,
            isDark = record.shareBrightnessDark ?: true,
        )
        call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }

    // 公开 K 线接口
    route("/api/v1/public/share/{token}") {
        get("/candles") {
            val token = call.parameters["token"]
            if (token.isNullOrBlank() || !isPlausibleToken(token)) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("INVALID_PARAMETER", "token 无效")
                )
            }

            val ip = call.clientIp()
            if (!ShareRateLimiter.allow(ip)) {
                return@get call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResponse.error<String>("RATE_LIMITED", "请求过于频繁")
                )
            }

            val key = call.parameters["key"]
            val tsCode = call.parameters["tsCode"]
            val periodStr = call.parameters["period"]
            if (key.isNullOrBlank() || tsCode.isNullOrBlank() || periodStr.isNullOrBlank()) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("INVALID_PARAMETER", "缺少必要参数")
                )
            }

            // 严格校验：请求参数必须与分享时刻写入的白名单完全一致
            val entry = AgentShareKlineAllowlistRepository.find(token, key)
            if (entry == null) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    ApiResponse.error<String>("FORBIDDEN", "该 K 线请求未授权")
                )
            }
            if (entry.tsCode != tsCode || entry.period != periodStr) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    ApiResponse.error<String>("FORBIDDEN", "参数与分享时刻不一致")
                )
            }

            val period = runCatching { CandlePeriod.valueOf(periodStr) }.getOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("INVALID_PARAMETER", "period 不支持")
                )

            val request = CandleSubscribeRequest(
                tsCode = tsCode,
                period = period,
                limit = entry.limitCount,
                startDate = entry.startDate,
                endDate = entry.endDate,
                useAdjusted = entry.useAdjusted,
            )

            val candleKey = CandleKey(tsCode = tsCode, period = period)
            val snapshot = DataProviderBootstrap.candleFacade.readSnapshot(candleKey)
            if (snapshot == null) {
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<String>("PROVIDER_NOT_READY", "K 线数据尚未就绪")
                )
            }

            val payload = DataProviderBootstrap.candleProjectionService.project(
                key = candleKey,
                request = request,
                snapshot = snapshot,
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(payload))
        }
    }
}

private fun isPlausibleToken(token: String): Boolean =
    token.length in 8..40 && token.all { it.isLetterOrDigit() }

private fun ApplicationCall.clientIp(): String? =
    request.origin.remoteHost.takeIf { it.isNotBlank() }

private fun renderNotFound(message: String): String = """
    <!DOCTYPE html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8" />
      <title>${htmlEscape(message)}</title>
      <link rel="stylesheet" href="/static/share/share.css" />
    </head>
    <body class="share-empty">
      <div class="share-empty-inner">
        <p class="share-empty-icon">⌀</p>
        <p class="share-empty-message">${htmlEscape(message)}</p>
      </div>
    </body>
    </html>
""".trimIndent()

/**
 * 异步记录分享访问；用 application 级 supervisor scope，避免阻塞响应。
 */
private object ShareViewLogger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recordAsync(token: String, ip: String?, userAgent: String?) {
        val ipHash = ShareTokenGenerator.ipHash(ip)
        scope.launch {
            runCatching {
                AgentShareViewLogRepository.record(token, ipHash, userAgent)
            }
        }
    }
}

/**
 * 极简内存级 IP 限流：每 IP 60 次/分钟。
 *
 * 分享 K 线接口是低频访问，不引入 Redis 等额外依赖。重启后重新计数。
 */
private object ShareRateLimiter {
    private const val LIMIT_PER_MINUTE = 60L
    private const val WINDOW_MS = 60_000L

    private data class Counter(val windowStart: AtomicLong, val count: AtomicLong)

    private val counters = ConcurrentHashMap<String, Counter>()

    fun allow(ip: String?): Boolean {
        val key = ip ?: "unknown"
        val now = System.currentTimeMillis()
        val counter = counters.computeIfAbsent(key) {
            Counter(AtomicLong(now), AtomicLong(0L))
        }
        val windowStart = counter.windowStart.get()
        if (now - windowStart >= WINDOW_MS) {
            counter.windowStart.set(now)
            counter.count.set(1)
            return true
        }
        return counter.count.incrementAndGet() <= LIMIT_PER_MINUTE
    }
}

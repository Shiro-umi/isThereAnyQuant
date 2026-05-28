package ktor.module

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.path

private const val LongLivedCacheSeconds = 365 * 24 * 60 * 60
private const val ShortLivedCacheSeconds = 60 * 60

fun Application.configureStaticContent() {
    install(DefaultHeaders)
    install(AutoHeadResponse)
    install(PartialContent)
    install(ConditionalHeaders)

    // 手动设置静态资源缓存头（替代 CachingHeaders 插件）。
    // 直接拼接完整 Cache-Control 值，确保 long-lived 资源带上 immutable 指令。
    sendPipeline.intercept(ApplicationSendPipeline.TransferEncoding) { message ->
        if (message !is OutgoingContent) return@intercept
        val call = context
        val path = call.request.path()
        if (!call.isWebStaticRequest(path)) return@intercept

        val cacheControl = when {
            path == "/" ||
                path == "/index.html" ||
                path == "/sw.js" ||
                path == "/asset-manifest.json" ->
                "no-store, public"

            path == "/manifest.json" ->
                "max-age=$ShortLivedCacheSeconds, public"

            path.hasLongLivedStaticExtension() || message.contentType.isLongLivedStaticContentType() ->
                // immutable 明确告诉浏览器：在 max-age 有效期内该资源内容绝对不变，
                // 即使在刷新或重启浏览器后也不要发送验证请求（If-None-Match / If-Modified-Since）。
                // 对于 webpack 输出的 [hash].wasm / [hash].js 这类内容寻址资源尤其重要。
                "max-age=$LongLivedCacheSeconds, s-maxage=$LongLivedCacheSeconds, public, immutable"

            else -> null
        }

        if (cacheControl != null) {
            call.response.headers.append(HttpHeaders.CacheControl, cacheControl)
        }
    }
}

private fun ApplicationCall.isWebStaticRequest(path: String): Boolean =
    path == "/" ||
        path == "/index.html" ||
        path == "/sw.js" ||
        path == "/asset-manifest.json" ||
        path == "/manifest.json" ||
        path == "/styles.css" ||
        path == "/compose-app.js" ||
        path.startsWith("/composeResources/") ||
        path.hasLongLivedStaticExtension()

private fun String.hasLongLivedStaticExtension(): Boolean =
    endsWith(".wasm") ||
        endsWith(".js") ||
        endsWith(".css") ||
        endsWith(".ttf") ||
        endsWith(".otf") ||
        endsWith(".woff") ||
        endsWith(".woff2") ||
        endsWith(".svg") ||
        endsWith(".png") ||
        endsWith(".webp") ||
        endsWith(".ico")

private fun ContentType?.isLongLivedStaticContentType(): Boolean =
    this?.withoutParameters() in setOf(
        ContentType.Application.Wasm,
        ContentType.Application.JavaScript,
        ContentType.Text.CSS,
        ContentType.Image.SVG,
        ContentType.Font.Any
    )

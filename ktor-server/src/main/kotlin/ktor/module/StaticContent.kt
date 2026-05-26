package ktor.module

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.path

private const val LongLivedCacheSeconds = 365 * 24 * 60 * 60
private const val ShortLivedCacheSeconds = 60 * 60

fun Application.configureStaticContent() {
    install(DefaultHeaders)
    install(AutoHeadResponse)
    install(PartialContent)
    install(CachingHeaders) {
        options { call, content ->
            val path = call.request.path()
            if (!call.isWebStaticRequest(path)) return@options null

            when {
                path == "/" ||
                    path == "/index.html" ||
                    path == "/sw.js" ||
                    path == "/asset-manifest.json" ->
                    CachingOptions(CacheControl.NoStore(CacheControl.Visibility.Public))

                path == "/manifest.json" ->
                    CachingOptions(
                        CacheControl.MaxAge(
                            maxAgeSeconds = ShortLivedCacheSeconds,
                            visibility = CacheControl.Visibility.Public
                        )
                    )

                path.hasLongLivedStaticExtension() || content.contentType.isLongLivedStaticContentType() ->
                    CachingOptions(
                        CacheControl.MaxAge(
                            maxAgeSeconds = LongLivedCacheSeconds,
                            proxyMaxAgeSeconds = LongLivedCacheSeconds,
                            visibility = CacheControl.Visibility.Public
                        )
                    )

                else -> null
            }
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

package org.shiroumi.quant_kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.charsets.Charsets
import kotlinx.serialization.json.Json
import kotlin.time.Duration

// jsMain
actual fun createHttpClient(): HttpClient {
    return HttpClient(Js) {
        install(ContentNegotiation) {
            json(
                json = Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                },
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
            )
        }
//        // 安装日志插件，便于调试
//        install(Logging) {
//            level = LogLevel.ALL
//        }
        install(WebSockets) {
            pingInterval = Duration.parse("15s")
        }
    }
}
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import kotlinx.browser.window
import kotlin.js.toJsString
import kotlin.time.Duration

/**
 * JS 平台使用 Js 引擎创建 HttpClient
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Js) {
        // JS 引擎配置
        engine {
            configureRequest {
                // 允许跨域请求携带 Cookie
                credentials = "include".toJsString()
            }
        }

        // 应用通用配置
        configureCommon()

        // WebSocket 支持
        install(WebSockets) {
            pingInterval = Duration.parse("15s")
        }
    }
}

/**
 * JS 平台判断是否开发环境
 * 通过当前主机名判断
 */
actual fun isDevelopmentMode(): Boolean {
    val hostname = window.location.hostname
    return hostname == "localhost" || hostname == "127.0.0.1"
}

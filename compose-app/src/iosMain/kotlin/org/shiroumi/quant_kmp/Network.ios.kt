package org.shiroumi.quant_kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import org.shiroumi.config.AppConfig
import kotlin.time.Duration

/**
 * iOS 平台使用 Darwin 引擎（NSURLSession）创建 HttpClient。
 * 与 Android CIO / Web Js 共享 configureCommon()，业务语义一致。
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        // 应用通用配置：ContentNegotiation、超时、默认 baseUrl、开发日志。
        configureCommon()

        // WebSocket 支持，与其它平台同样 15s ping。
        install(WebSockets) {
            pingInterval = Duration.parse("15s")
        }
    }
}

/**
 * iOS 判断是否开发环境：直接读编译期锁定的运行模式。
 * 与架构约定一致——前端网络地址与运行模式都是编译期值。
 */
actual fun isDevelopmentMode(): Boolean {
    return AppConfig.mode.contains("debug", ignoreCase = true)
}

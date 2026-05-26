package org.shiroumi.quant_kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.websocket.WebSocketDeflateExtension
import java.util.zip.Deflater
import kotlin.time.Duration

/**
 * Android 平台使用 CIO 引擎创建 HttpClient
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(CIO) {
        // CIO 引擎配置
        engine {
            // 最大并发连接数
            maxConnectionsCount = 100
            // 请求超时配置
            requestTimeout = 30000
        }

        // 应用通用配置
        configureCommon()

        // 接收服务端 Set-Cookie，作为 Android 进程内的 Cookie 兼容层。
        install(HttpCookies)

        // WebSocket 支持
        install(WebSockets) {
            pingInterval = Duration.parse("15s")
            // 与服务端 permessage-deflate 协商压缩，降低 K 线 JSON 等大帧的公网传输字节数。
            // wasmJs 不在此处启用——浏览器原生 WebSocket 由浏览器底层完成协商。
            extensions {
                install(WebSocketDeflateExtension) {
                    compressionLevel = Deflater.DEFAULT_COMPRESSION
                    compressIfBiggerThan(4 * 1024)
                }
            }
        }
    }
}

/**
 * Android 平台判断是否开发环境
 * 通过 BuildConfig 检查
 */
actual fun isDevelopmentMode(): Boolean {
    return try {
        // 尝试通过反射获取 BuildConfig.DEBUG
        val buildConfigClass = Class.forName("org.shiroumi.quant_kmp.BuildConfig")
        val debugField = buildConfigClass.getField("DEBUG")
        debugField.getBoolean(null)
    } catch (e: Exception) {
        // 如果获取失败，默认返回 false（生产环境）
        false
    }
}

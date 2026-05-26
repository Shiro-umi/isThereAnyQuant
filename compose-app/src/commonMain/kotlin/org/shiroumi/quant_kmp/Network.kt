package org.shiroumi.quant_kmp

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import org.shiroumi.config.AppConfig

/**
 * 创建平台特定的 HttpClient
 * 由各个平台实现具体的引擎配置
 */
expect fun createPlatformHttpClient(): HttpClient

/**
 * 判断是否为开发环境
 * 各平台自行实现
 */
expect fun isDevelopmentMode(): Boolean

/**
 * 全局 JSON 序列化配置
 */
val AppJson = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * 统一的 HttpClient 配置
 * 所有平台共享相同的配置逻辑
 * 这是一个 HttpClientConfig 的扩展函数，可在各平台的 HttpClient 配置块中直接调用
 */
fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureCommon() {
    // 内容协商 - JSON
    install(ContentNegotiation) {
        json(AppJson)
    }

    // 超时配置
    install(HttpTimeout) {
        requestTimeoutMillis = 30000
        connectTimeoutMillis = 10000
        socketTimeoutMillis = 30000
    }

    // 默认请求配置
    defaultRequest {
        url(AppConfig.apiBaseUrl)
        contentType(ContentType.Application.Json)
    }

    // 日志配置（仅在开发环境启用）
    if (isDevelopmentMode()) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    println("[HTTP] $message")
                }
            }
            level = LogLevel.ALL
        }
    }
}

/**
 * WebSocket 客户端
 * 用于与后端 WebSocket 服务通信
 */
class SocketClient {

    private val client: HttpClient by lazy {
        createPlatformHttpClient()
    }

    private var session: WebSocketSession? = null

    suspend fun open(onConnected: () -> Unit = {}, onReceiveText: (text: String) -> Unit) {
        val session = client.webSocketSession("${AppConfig.wsBaseUrl}/tasks")
        this.session = session
        onConnected()
        println("Connection established! Ready to communicate.")
        val initialMessage = "Hello from Ktor Client!"
        session.send(Frame.Text(initialMessage))
        println("Sent: '$initialMessage'")

        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    val receivedText = frame.readText()
                    onReceiveText(receivedText)
                    if (receivedText.equals("close", ignoreCase = true)) {
                        session.close(CloseReason(CloseReason.Codes.NORMAL, "Client requested close"))
                        println("Closing connection...")
                    }
                }

                is Frame.Binary -> println("Received binary data: ${frame.readBytes().size} bytes")
                is Frame.Close -> {
                    println("Server closed the connection: ${frame.readReason()}")
                    break
                }

                is Frame.Ping -> Unit
                is Frame.Pong -> Unit
                else -> Unit
            }
        }
    }

    suspend fun close() {
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client requested close"))
        session = null
    }
}

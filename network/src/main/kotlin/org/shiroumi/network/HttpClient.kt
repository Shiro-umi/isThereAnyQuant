package org.shiroumi.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.shiroumi.config.AppConfig
import org.shiroumi.config.ConfigHolder

/**
 * JSON 配置
 */
@OptIn(ExperimentalSerializationApi::class)
val defaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    prettyPrint = false
}

/**
 * 创建配置好的 Ktor HttpClient (CIO引擎)
 */
fun createHttpClient(
    json: Json = defaultJson,
    keepAliveTimeMillis: Long = 0,
    requestTimeoutMillis: Long = DEFAULT_HTTP_REQUEST_TIMEOUT_MS,
    connectTimeoutMillis: Long = DEFAULT_HTTP_CONNECT_TIMEOUT_MS,
    socketTimeoutMillis: Long = DEFAULT_HTTP_SOCKET_TIMEOUT_MS
): HttpClient {
    return HttpClient(CIO) {
        // 连接配置
        engine {
            requestTimeout = requestTimeoutMillis
            endpoint {
                connectTimeout = connectTimeoutMillis
                keepAliveTime = keepAliveTimeMillis
            }
        }

        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
            this.connectTimeoutMillis = connectTimeoutMillis
            this.socketTimeoutMillis = socketTimeoutMillis
        }

        // 默认请求配置
        install(DefaultRequest) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }

        // JSON 序列化
        install(ContentNegotiation) {
            json(json)
        }

        // 高频 realtime 请求会淹没业务日志，这里统一关闭底层 HTTP 请求日志。
        install(Logging) {
            level = LogLevel.NONE
            logger = Logger.DEFAULT
        }
    }
}

private const val DEFAULT_HTTP_REQUEST_TIMEOUT_MS = 60_000L
private const val DEFAULT_HTTP_SOCKET_TIMEOUT_MS = 60_000L
private const val DEFAULT_HTTP_CONNECT_TIMEOUT_MS = 5_000L

/**
 * JVM 命令行工具共用的 HTTP 入口。
 *
 * 这些工具都是短生命周期进程，但同一个进程里仍可能执行多个命令。
 * 统一复用 client 可以避免每个命令重复初始化 engine，也让本机服务地址解析保持一致。
 */
object SharedHttpClient {
    val client: HttpClient by lazy {
        createHttpClient(keepAliveTimeMillis = 5_000).also { client ->
            Runtime.getRuntime().addShutdownHook(Thread {
                client.close()
            })
        }
    }

    fun localServerUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "http://127.0.0.1:${resolveServerPort()}$normalizedPath"
    }

    private fun resolveServerPort(): Int {
        return System.getenv("QUANT_SERVER_PORT")?.toIntOrNull()
            ?: try {
                AppConfig.port
            } catch (_: Exception) {
                ConfigHolder.config.server.port
            }
    }
}

/**
 * 带动态认证的 HTTP Client 包装类
 */
class ApiClient(val baseUrl: String) {
    val http = createHttpClient()

    /**
     * 获取当前 host 对应的 API Key
     */
    @PublishedApi
    internal fun getApiKey(): String? {
        val config = ConfigHolder.config
        return when {
            baseUrl.contains("deepseek.com") -> {
                config.llm.models["deepseek-chat"]?.apiKey
            }
            baseUrl.contains("siliconflow.cn") -> {
                config.llm.models["siliconflow"]?.apiKey
            }
            else -> null
        }
    }

    /**
     * POST 请求
     */
    suspend inline fun <reified T> post(
        path: String,
        body: Any
    ): T {
        val apiKey = getApiKey()
        return http.post {
            url("$baseUrl$path")
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(body)
        }.body()
    }

    /**
     * POST 请求（流式响应）
     */
    suspend fun postStream(
        path: String,
        body: Any
    ): HttpResponse {
        val apiKey = getApiKey()
        return http.post {
            url("$baseUrl$path")
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(body)
        }
    }
}

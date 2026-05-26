package org.shiroumi.quant_kmp.service

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import model.DataUpdateStatus
import model.ws.WsTopic

/**
 * 前端数据更新服务
 * 监听全局WebSocket并管理状态
 */
class DataUpdateService {

    private val _status = MutableStateFlow(DataUpdateStatus())
    val status: StateFlow<DataUpdateStatus> = _status.asStateFlow()

    private val _shouldShowIndicator = MutableStateFlow(false)
    val shouldShowIndicator: StateFlow<Boolean> = _shouldShowIndicator.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isStarted = false
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 启动服务
     */
    fun start() {
        if (isStarted) {
            println("[DataUpdateService] Already started, skip")
            return
        }

        println("[DataUpdateService] Starting...")
        isStarted = true

        // 1. 立即拉取一次初始状态，确保 UI 即使在没有收到 WS 广播时也是准确的
        scope.launch {
            try {
                // 使用 Ktor HttpClient 获取状态
                val httpClient = org.shiroumi.quant_kmp.di.HttpClientProvider.apiClient
                val baseUrl = org.shiroumi.config.AppConfig.apiBaseUrl
                
                // 注意：这里需要处理可能的路径拼接问题
                val response = httpClient.get("$baseUrl/api/data/status")
                val responseText = response.bodyAsText()
                
                // 解析响应，结构为 { "status": DataUpdateStatus, ... }
                val root = json.parseToJsonElement(responseText)
                val statusElement = root.jsonObject["status"]
                if (statusElement != null) {
                    val initialStatus = json.decodeFromJsonElement<DataUpdateStatus>(statusElement)
                    _status.value = initialStatus
                    _shouldShowIndicator.value = initialStatus.shouldShowIndicator
                    println("[DataUpdateService] Initial status synced: ${initialStatus.state}, lastUpdate: ${initialStatus.lastUpdateTime}")
                }
            } catch (e: Exception) {
                println("[DataUpdateService] Failed to fetch initial status: ${e.message}")
            }
        }

        // 2. 监听全局 WebSocket 事件流
        scope.launch {
            GlobalWebSocketClient.eventsFlow.collect { event ->
                if (event.topic == WsTopic.DATA_UPDATE) {
                    event.payload?.let { payloadStr ->
                        try {
                            val newStatus = json.decodeFromString<DataUpdateStatus>(payloadStr)
                            _status.value = newStatus
                            _shouldShowIndicator.value = newStatus.shouldShowIndicator
                        } catch (e: Exception) {
                            println("[DataUpdateService] Failed to parse DataUpdateStatus: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * 停止服务
     */
    fun stop() {
        // GlobalWebSocketClient 的生命周期由外部管理，此处不再断开连接
        isStarted = false
    }

    companion object {
        private var instance: DataUpdateService? = null

        fun getInstance(): DataUpdateService {
            return instance ?: DataUpdateService().also { instance = it }
        }
    }
}

/**
 * 格式化时间（毫秒）为可读字符串
 */
fun formatDuration(millis: Long): String {
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000

    return when {
        hours > 0 -> "${hours}小时${minutes}分钟"
        minutes > 0 -> "${minutes}分钟"
        else -> "${millis / 1000}秒"
    }
}

/**
 * 格式化时间戳为日期时间字符串
 */
fun formatDateTime(timestamp: Long): String {
    // 简化的格式化
    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(timestamp)
    val local = instant.toString()
    return local.substring(5, 16).replace("T", " ") // 返回 "MM-dd HH:mm" 格式
}

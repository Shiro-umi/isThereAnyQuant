package org.shiroumi.quant_kmp.service

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import model.DataUpdateStatus
import model.ws.WsTopic

/**
 * 前端数据更新服务
 *
 * 数据更新状态由两条上游合并而成：进入页面时拉取一次的初始 HTTP 快照，以及全局
 * WebSocket 持续推送的 [WsTopic.DATA_UPDATE] 增量。两者统一经 [stateIn] 惰性绑定，
 * 订阅者归零即停整条上游、首个订阅者到来时自动重启——指示器无需再手写 start/stop，
 * 也就不存在挂载多次累积出多份 collect 协程的泄漏。
 */
class DataUpdateService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    /** 进入页面时拉取一次初始状态，确保即使没有收到 WS 广播 UI 也是准确的。 */
    private val initialStatusFlow = flow {
        try {
            val httpClient = org.shiroumi.quant_kmp.di.HttpClientProvider.apiClient
            val baseUrl = org.shiroumi.config.AppConfig.apiBaseUrl
            val response = httpClient.get("$baseUrl/api/data/status")
            val root = json.parseToJsonElement(response.bodyAsText())
            root.jsonObject["status"]?.let { statusElement ->
                val initialStatus = json.decodeFromJsonElement<DataUpdateStatus>(statusElement)
                println("[DataUpdateService] Initial status synced: ${initialStatus.state}, lastUpdate: ${initialStatus.lastUpdateTime}")
                emit(initialStatus)
            }
        } catch (e: Exception) {
            println("[DataUpdateService] Failed to fetch initial status: ${e.message}")
        }
    }

    /** 全局 WebSocket 推送的实时数据更新状态。 */
    private val pushedStatusFlow = GlobalWebSocketClient.eventsFlow.mapNotNull { event ->
        if (event.topic != WsTopic.DATA_UPDATE) return@mapNotNull null
        event.payload?.let { payloadStr ->
            try {
                json.decodeFromString<DataUpdateStatus>(payloadStr)
            } catch (e: Exception) {
                println("[DataUpdateService] Failed to parse DataUpdateStatus: ${e.message}")
                null
            }
        }
    }

    val status: StateFlow<DataUpdateStatus> =
        merge(initialStatusFlow, pushedStatusFlow)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), DataUpdateStatus())

    val shouldShowIndicator: StateFlow<Boolean> =
        status.map { it.shouldShowIndicator }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

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

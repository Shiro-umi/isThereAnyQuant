package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import model.candle.StrategySentimentResponse
import model.ws.IntradaySnapshotPayload
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient

/**
 * 情绪页面 ViewModel。
 *
 * 当前页面的数据来源已经稳定分层：
 * - 历史序列：继续通过 HTTP 读取已落库的历史情绪曲线
 * - 当前快照：只通过 `INTRADAY_SNAPSHOT` 订阅新 runtime 投影
 *
 * 这里不再承担任何旧盘中快照恢复或本地重算职责。
 */
class SentimentViewModel(
    private val repository: org.shiroumi.quant_kmp.feature.candle.domain.repository.CandleRepository
) : ViewModel() {
    private companion object {
        const val RETRY_DELAY_MS = 1_000L
        const val INTRADAY_SNAPSHOT_OWNER = "sentiment"
    }

    private val _history = MutableStateFlow<List<StrategySentimentResponse>>(emptyList())
    val history: StateFlow<List<StrategySentimentResponse>> = _history.asStateFlow()

    private val _currentSentiment = MutableStateFlow<StrategySentimentResponse?>(null)
    val currentSentiment: StateFlow<StrategySentimentResponse?> = _currentSentiment.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _snapshotError = MutableStateFlow<String?>(null)
    val snapshotError: StateFlow<String?> = _snapshotError.asStateFlow()

    private var retryJob: Job? = null
    private var retryCount = 0

    init {
        loadSentiment()
        observeIntradaySnapshot()
        observeIntradaySnapshotError()
    }

    private fun loadSentiment() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.getStrategySentiment(limit = 120)
                _history.value = result.getOrNull() ?: emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeIntradaySnapshot() {
        viewModelScope.launch {
            GlobalWebSocketClient.intradaySnapshotFlow.collect { snapshot ->
                if (snapshot != null) {
                    _currentSentiment.value = snapshot.toStrategySentimentResponse()
                    _snapshotError.value = null
                    retryCount = 0
                    retryJob?.cancel()
                    retryJob = null
                }
            }
        }
    }

    /**
     * 订阅盘中快照错误并做有限自动重试。
     */
    private fun observeIntradaySnapshotError() {
        viewModelScope.launch {
            GlobalWebSocketClient.intradaySnapshotErrorFlow.collect { errorMessage ->
                _snapshotError.value = errorMessage
                if (errorMessage != null && errorMessage.isRetryable()) {
                    scheduleRetry()
                }
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            var delayMs = RETRY_DELAY_MS
            val maxDelayMs = 30_000L
            while (isActive && _currentSentiment.value == null && _snapshotError.value?.isRetryable() == true) {
                delay(delayMs)
                if (_currentSentiment.value != null || !(_snapshotError.value?.isRetryable() == true)) {
                    break
                }
                GlobalWebSocketClient.subscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
            retryCount = 0
        }
    }
}

private fun String.isRetryable(): Boolean =
    contains("初始化") || contains("未就绪") || contains("准备")

private fun IntradaySnapshotPayload.toStrategySentimentResponse(): StrategySentimentResponse {
    val sentiment = sentiment
    return StrategySentimentResponse(
        tradeDate = sentiment.tradeDate,
        sentimentExposure = sentiment.sentimentExposure,
        bullRatio = sentiment.bullRatio,
        marketVol = sentiment.marketVol,
        fftScore = sentiment.fftScore,
        residualScore = sentiment.residualScore,
        accelZ = sentiment.accelZ,
        volZ = sentiment.volZ,
        selectedCount = portfolio.count { it.selected },
        emptyReason = sentiment.reason,
        ratioNorm = sentiment.ratioNorm,
        volScore = sentiment.volScore,
        accelScore = sentiment.accelScore,
        absoluteFloor = sentiment.absoluteFloor,
        volCap = sentiment.volCap,
    )
}

fun List<StrategySentimentResponse>.withRealtimeSentiment(
    realtime: StrategySentimentResponse?,
): List<StrategySentimentResponse> {
    if (realtime == null) return this
    if (isEmpty()) return listOf(realtime)

    val last = last()
    return when {
        last.tradeDate == realtime.tradeDate -> dropLast(1) + realtime
        else -> this + realtime
    }
}

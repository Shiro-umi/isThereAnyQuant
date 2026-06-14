package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import model.candle.CandleChartData
import model.candle.Exchange
import model.candle.StockInfo
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.data.candle.CandleRepository
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.ui.core.mvi.MviViewModel
import kotlin.uuid.ExperimentalUuidApi

data class SelectedStockRef(
    val node: StrategyTrackingStockNode,
    val section: StrategyTrackingSection,
    val tradeDate: String,
    val cardKey: String,
)

data class SelectedStockDetail(
    val selected: SelectedStockRef,
    val candleData: CandleChartData? = null,
    val stockInfo: StockInfo? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class TrackingDetailOverlayState(
    val node: StrategyTrackingStockNode,
    val section: StrategyTrackingSection,
    val tradeDate: String,
    val cardKey: String,
    val candleData: CandleChartData?,
    val stockInfo: StockInfo?,
    val isLoading: Boolean,
    val error: String?,
)

data class TrackingOverlayAnchorState(
    val node: StrategyTrackingStockNode,
    val section: StrategyTrackingSection,
    val tradeDate: String,
    val cardKey: String,
)

/**
 * 最早跟随日校准状态。激活时跟踪页渲染 [timeline]（服务端以 [followStartDate]
 * 空仓起步重放生产持仓规则的结果），替代模型自身持仓流。
 */
data class TrackingCalibration(
    val followStartDate: String,
    val timeline: StrategyPositionTrackingTimeline? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * 策略持仓跟踪页 UI 状态。收敛原先并列的 7 个 StateFlow 为单一状态。
 *
 * [isLoadingTracking] 承载原 isLoading 流语义；为满足 [UiState] 契约用计算属性 [isLoading] 暴露。
 */
data class StrategyTrackingUiState(
    val timeline: StrategyPositionTrackingTimeline? = null,
    val isLoadingTracking: Boolean = true,
    val error: String? = null,
    val selectedStock: SelectedStockRef? = null,
    val selectedDetail: SelectedStockDetail? = null,
    val calibration: TrackingCalibration? = null,
    val listObservedDate: String? = null,
) : org.shiroumi.quant_kmp.ui.core.mvi.UiState {
    override val isLoading: Boolean get() = isLoadingTracking
    override val errorMessage: String? get() = error
}

sealed interface StrategyTrackingAction : org.shiroumi.quant_kmp.ui.core.mvi.UiAction {
    data object Refresh : StrategyTrackingAction
    data class SelectListObservedDate(val tradeDate: String?) : StrategyTrackingAction
    data class CalibrateFollowStart(val followStartDate: String) : StrategyTrackingAction
    data object ClearCalibration : StrategyTrackingAction
    data object DismissDetail : StrategyTrackingAction
    data class SelectStock(
        val node: StrategyTrackingStockNode,
        val section: StrategyTrackingSection,
        val tradeDate: String,
    ) : StrategyTrackingAction
}

/** 该页当前无一次性副作用，保留空标记以满足 [MviViewModel] 契约。 */
sealed interface StrategyTrackingEffect : org.shiroumi.quant_kmp.ui.core.mvi.UiEffect

class StrategyPositionTrackingViewModel(
    private val repository: CandleRepository,
) : ViewModel(),
    MviViewModel<StrategyTrackingUiState, StrategyTrackingAction, StrategyTrackingEffect> {
    private companion object {
        const val STRATEGY_TRACKING_OWNER = "tracking"
    }

    private val _state = MutableStateFlow(StrategyTrackingUiState())
    override val state: StateFlow<StrategyTrackingUiState> = _state.asStateFlow()

    /** 当前无一次性事件，留空流即可。 */
    override val effect: Flow<StrategyTrackingEffect> = emptyFlow()

    private var detailLoadJob: Job? = null
    private val stockInfoCache = linkedMapOf<String, StockInfo>()

    // 与 CandleViewModel/SentimentViewModel 一致的引用计数：同一 ViewModel 可被多个 NavEntry
    // 同时持有，用计数代替 boolean，避免新 entry 已 enter 后被旧 entry 的 onScreenLeave 误清理。
    private var screenActiveRefCount: Int = 0
    private val isScreenActive: Boolean get() = screenActiveRefCount > 0
    private var screenLeaveCleanupJob: Job? = null

    init {
        observeStrategyPositionTracking()
    }

    /**
     * 跟踪页进入前台可见区域：订阅持仓跟踪 topic。
     *
     * 订阅生命周期收敛到 ViewModel 单一所有者，从 0→1 时才真正下发订阅命令。
     */
    fun onScreenEnter() {
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = null
        val wasInactive = screenActiveRefCount == 0
        screenActiveRefCount += 1
        if (!wasInactive) return
        GlobalWebSocketClient.subscribeStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
    }

    /**
     * 跟踪页离开可见区域：300ms 防抖后撤销订阅，避免快速切页时误解订。
     */
    fun onScreenLeave() {
        if (screenActiveRefCount == 0) return
        screenActiveRefCount -= 1
        if (screenActiveRefCount > 0) return
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = viewModelScope.launch {
            delay(300)
            if (!isScreenActive) {
                GlobalWebSocketClient.unsubscribeStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
            }
            screenLeaveCleanupJob = null
        }
    }

    override fun dispatch(action: StrategyTrackingAction) {
        when (action) {
            is StrategyTrackingAction.Refresh -> refresh()
            is StrategyTrackingAction.SelectListObservedDate -> selectListObservedDate(action.tradeDate)
            is StrategyTrackingAction.CalibrateFollowStart -> calibrateFollowStart(action.followStartDate)
            is StrategyTrackingAction.ClearCalibration -> clearCalibration()
            is StrategyTrackingAction.DismissDetail -> dismissDetail()
            is StrategyTrackingAction.SelectStock -> selectStock(action.node, action.section, action.tradeDate)
        }
    }

    private fun refresh() {
        GlobalWebSocketClient.refreshStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
    }

    /** 切换列表浏览观察日；传 null 回到最新日。校准/模型流共用，仅影响列表面板。 */
    private fun selectListObservedDate(tradeDate: String?) {
        _state.update { it.copy(listObservedDate = tradeDate) }
    }

    /** 激活最早跟随日校准：以 [followStartDate] 为第一笔跟随买入日拉取重放视图，并通过 WS 同步到服务端。 */
    private fun calibrateFollowStart(followStartDate: String) {
        // 新的用户意图作废任何旧的订阅错误，避免上一轮残留错误混入本次校准展示。
        _state.update {
            it.copy(
                calibration = TrackingCalibration(followStartDate = followStartDate),
                listObservedDate = null,
                error = null,
            )
        }
        GlobalWebSocketClient.setTrackingFollowStartDate(followStartDate)
    }

    /** 清除校准，回到模型自身持仓流，并通过 WS 清空服务端设置。 */
    private fun clearCalibration() {
        // 清除是明确的用户意图，连同主错误流一并复位，防止校准期积压的迟到 ERROR 在清除后冒出。
        _state.update {
            it.copy(
                calibration = null,
                listObservedDate = null,
                error = null,
            )
        }
        GlobalWebSocketClient.setTrackingFollowStartDate("")
    }

    private fun dismissDetail() {
        detailLoadJob?.cancel()
        _state.update { it.copy(selectedStock = null, selectedDetail = null) }
    }

    private fun selectStock(
        node: StrategyTrackingStockNode,
        section: StrategyTrackingSection,
        tradeDate: String,
    ) {
        val selected = SelectedStockRef(
            node = node,
            section = section,
            tradeDate = tradeDate,
            cardKey = trackingCardKey(
                tradeDate = tradeDate,
                section = section,
                stockCode = node.stockCode,
                slotIndex = node.slotIndex,
            ),
        )
        _state.update {
            it.copy(
                selectedStock = selected,
                selectedDetail = SelectedStockDetail(
                    selected = selected,
                    stockInfo = stockInfoCache[node.stockCode],
                    isLoading = true,
                ),
            )
        }
        loadSelectedDetail(selected)
    }

    /** 持仓跟踪订阅事件：数据帧与错误帧合并为单一事件流，串行消费以保证状态写入原子。 */
    private sealed interface TrackingEvent {
        data class Response(val response: StrategyPositionTrackingResponse?) : TrackingEvent
        data class Error(val message: String) : TrackingEvent
    }

    private fun observeStrategyPositionTracking() {
        // 数据帧与错误帧 merge 成单一事件流，由单协程串行处理：所有对 _timeline/_calibration/_error
        // 的写入都在同一协程内顺序发生，消除两个独立 collector 并发改共享状态导致的竞态
        // （错误一闪而过、清除校准后陈旧错误冒出等）。
        val errorEvents = GlobalWebSocketClient.strategyPositionTrackingErrorFlow
            .map<String, TrackingEvent> { TrackingEvent.Error(it) }
        val responseEvents = GlobalWebSocketClient.strategyPositionTrackingFlow
            .map<StrategyPositionTrackingResponse?, TrackingEvent> { TrackingEvent.Response(it) }
        viewModelScope.launch {
            merge(errorEvents, responseEvents).collectLatest { event ->
                when (event) {
                    is TrackingEvent.Error -> {
                        val active = _state.value.calibration
                        if (active != null) {
                            val reduced = CalibrationStateReducer.onError(active, event.message)
                            _state.update { it.copy(isLoadingTracking = false, calibration = reduced) }
                        } else {
                            // 非校准态的订阅错误写主错误流，由 timeline 兜底渲染失败态。
                            _state.update { it.copy(isLoadingTracking = false, error = event.message) }
                        }
                    }
                    is TrackingEvent.Response -> {
                        val response = event.response
                        if (response == null) {
                            _state.update { it.copy(isLoadingTracking = false, timeline = null) }
                            return@collectLatest
                        }
                        val timeline = response.toTimeline()
                        hydrateStockInfo(timeline)
                        val reducedCalibration = CalibrationStateReducer.onResponse(
                            current = _state.value.calibration,
                            wsFollowStartDate = response.followStartDate,
                            timeline = timeline,
                        )
                        _state.update {
                            it.copy(
                                isLoadingTracking = false,
                                error = null,
                                timeline = timeline,
                                calibration = reducedCalibration,
                            )
                        }

                        rebuildSelectedDetail()
                    }
                }
            }
        }
    }

    private fun hydrateStockInfo(timeline: StrategyPositionTrackingTimeline) {
        timeline.days.flatMap { day -> day.selection + day.holdings + day.cleared }
            .forEach { node ->
                if (stockInfoCache.containsKey(node.stockCode)) return@forEach
                stockInfoCache[node.stockCode] = buildPlaceholderStockInfo(
                    code = node.stockCode,
                    name = node.stockName
                )
            }
    }

    private fun loadSelectedDetail(selected: SelectedStockRef) {
        detailLoadJob?.cancel()
        detailLoadJob = viewModelScope.launch {
            try {
                val buyLocalDate = selected.node.buyDate?.let { LocalDate.parse(it) }
                val anchorDate = when (selected.section) {
                    StrategyTrackingSection.CLEARED -> LocalDate.parse(selected.tradeDate)
                    else -> buyLocalDate ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
                }
                val startDate = anchorDate.minus(DatePeriod(days = 48))
                val endDate = anchorDate.plus(DatePeriod(days = 7))

                val candleResult = repository.getStockCandles(selected.node.stockCode, startDate, endDate)
                val infoResult = repository.getStockByCode(selected.node.stockCode)
                val historicalCandleData = candleResult.getOrElse {
                    if (_state.value.selectedStock?.cardKey == selected.cardKey) {
                        _state.update { state ->
                            state.copy(
                                selectedDetail = state.selectedDetail?.copy(
                                    isLoading = false,
                                    error = it.message ?: "K线数据加载失败"
                                )
                            )
                        }
                    }
                    return@launch
                }
                val stockInfo = infoResult.getOrNull()
                stockInfo?.let { stockInfoCache[it.code] = it }

                if (_state.value.selectedStock?.cardKey != selected.cardKey) {
                    return@launch
                }

                _state.update {
                    it.copy(
                        selectedDetail = SelectedStockDetail(
                            selected = selected,
                            candleData = historicalCandleData,
                            stockInfo = stockInfo ?: stockInfoCache[selected.node.stockCode],
                            isLoading = false,
                            error = null,
                        )
                    )
                }
            } catch (e: Exception) {
                if (_state.value.selectedStock?.cardKey == selected.cardKey) {
                    _state.update { state ->
                        state.copy(
                            selectedDetail = state.selectedDetail?.copy(
                                isLoading = false,
                                error = e.message ?: "加载失败"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun rebuildSelectedDetail() {
        val current = _state.value.selectedDetail ?: return
        val timeline = _state.value.timeline ?: return

        val latestNode = timeline.days
            .flatMap { it.selection + it.holdings + it.cleared }
            .firstOrNull { it.stockCode == current.selected.node.stockCode && it.section == current.selected.section }

        if (latestNode != null && latestNode != current.selected.node) {
            _state.update {
                it.copy(
                    selectedDetail = current.copy(
                        selected = current.selected.copy(node = latestNode)
                    )
                )
            }
        }
    }

    private fun resolveStockName(code: String): String =
        stockInfoCache[code]?.name
            ?: _state.value.timeline?.days
                ?.asReversed()
                ?.asSequence()
                ?.flatMap { day -> (day.selection + day.holdings + day.cleared).asSequence() }
                ?.firstOrNull { it.stockCode == code }
                ?.stockName
            ?: code

    @OptIn(ExperimentalUuidApi::class)
    private fun buildPlaceholderStockInfo(
        code: String,
        name: String,
    ): StockInfo = StockInfo(
        code = code,
        name = name,
        exchange = parseExchange(code),
        industry = "",
        sector = "",
        latestPrice = 0f,
        changePercent = 0f,
        changeAmount = 0f,
        volume = 0f,
        turnover = 0f,
        marketCap = 0f,
        dayHigh = 0f,
        dayLow = 0f,
        openPrice = 0f,
        prevClose = 0f,
        updateTime = 0L
    )

    private fun parseExchange(code: String): Exchange = when {
        code.endsWith(".SH") -> Exchange.SH
        code.endsWith(".SZ") -> Exchange.SZ
        code.endsWith(".BJ") -> Exchange.BJ
        code.endsWith(".HK") -> Exchange.HK
        else -> Exchange.SZ
    }

    override fun onCleared() {
        super.onCleared()
        detailLoadJob?.cancel()
        screenActiveRefCount = 0
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = null
        // 兜底解订：防止 ViewModel 直接销毁而未走 onScreenLeave。
        GlobalWebSocketClient.unsubscribeStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
    }
}

fun SelectedStockRef.toOverlayState(detail: SelectedStockDetail? = null): TrackingDetailOverlayState =
    TrackingDetailOverlayState(
        node = selectedNode(detail),
        section = detail?.selected?.section ?: section,
        tradeDate = detail?.selected?.tradeDate ?: tradeDate,
        cardKey = detail?.selected?.cardKey ?: cardKey,
        candleData = detail?.candleData,
        stockInfo = detail?.stockInfo,
        isLoading = detail?.isLoading ?: true,
        error = detail?.error,
    )

fun SelectedStockRef.toOverlayAnchorState(): TrackingOverlayAnchorState =
    TrackingOverlayAnchorState(
        node = node,
        section = section,
        tradeDate = tradeDate,
        cardKey = cardKey,
    )

private fun SelectedStockRef.selectedNode(detail: SelectedStockDetail?): StrategyTrackingStockNode =
    detail?.selected?.node ?: node

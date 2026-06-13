package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.feature.candle.domain.repository.CandleRepository
import org.shiroumi.quant_kmp.feature.strategytracking.data.StrategyTrackingRepository
import org.shiroumi.quant_kmp.feature.strategytracking.data.TrackingCalibrationStore
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
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

class StrategyPositionTrackingViewModel(
    private val repository: CandleRepository,
    private val trackingRepository: StrategyTrackingRepository,
) : ViewModel() {
    private companion object {
        const val STRATEGY_TRACKING_OWNER = "tracking"
    }

    private val _timeline = MutableStateFlow<StrategyPositionTrackingTimeline?>(null)
    val timeline: StateFlow<StrategyPositionTrackingTimeline?> = _timeline.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedStock = MutableStateFlow<SelectedStockRef?>(null)
    val selectedStock: StateFlow<SelectedStockRef?> = _selectedStock.asStateFlow()

    private val _selectedDetail = MutableStateFlow<SelectedStockDetail?>(null)
    val selectedDetail: StateFlow<SelectedStockDetail?> = _selectedDetail.asStateFlow()

    private val _calibration = MutableStateFlow<TrackingCalibration?>(null)
    val calibration: StateFlow<TrackingCalibration?> = _calibration.asStateFlow()

    /**
     * 列表浏览的观察日（yyyy-MM-dd）。null = 跟随最新日（默认）。
     * 列表面板据此翻页展示窗口内任意确认交易日的持有/选股/清仓三列；全景流转图不受影响。
     */
    private val _listObservedDate = MutableStateFlow<String?>(null)
    val listObservedDate: StateFlow<String?> = _listObservedDate.asStateFlow()

    private var detailLoadJob: Job? = null
    private var calibrationLoadJob: Job? = null
    private val stockInfoCache = linkedMapOf<String, StockInfo>()

    init {
        observeStrategyPositionTracking()
        restorePersistedCalibration()
    }

    fun refresh() {
        GlobalWebSocketClient.refreshStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
        _calibration.value?.let { loadCalibration(it.followStartDate, keepCurrentTimeline = true) }
    }

    /** 切换列表浏览观察日；传 null 回到最新日。校准/模型流共用，仅影响列表面板。 */
    fun selectListObservedDate(tradeDate: String?) {
        _listObservedDate.value = tradeDate
    }

    /** 激活最早跟随日校准：以 [followStartDate] 为第一笔跟随买入日拉取重放视图，并持久化用户声明。 */
    fun calibrateFollowStart(followStartDate: String) {
        _calibration.value = TrackingCalibration(followStartDate = followStartDate)
        _listObservedDate.value = null
        persistCalibration(followStartDate)
        loadCalibration(followStartDate, keepCurrentTimeline = false)
    }

    /** 清除校准，回到模型自身持仓流，并清除持久化声明。 */
    fun clearCalibration() {
        calibrationLoadJob?.cancel()
        _calibration.value = null
        _listObservedDate.value = null
        persistCalibration(null)
    }

    /** 启动时恢复持久化的跟随起始日声明；空串/未设置则保持跟随全程。 */
    private fun restorePersistedCalibration() {
        viewModelScope.launch {
            val persisted = TrackingCalibrationStore.load()?.takeIf { it.isNotBlank() } ?: return@launch
            // 仅当尚未有活跃校准（避免覆盖会话内用户新选择）时恢复
            if (_calibration.value == null) {
                _calibration.value = TrackingCalibration(followStartDate = persisted)
                loadCalibration(persisted, keepCurrentTimeline = false)
            }
        }
    }

    private fun persistCalibration(followStartDate: String?) {
        viewModelScope.launch { TrackingCalibrationStore.save(followStartDate) }
    }

    private fun loadCalibration(followStartDate: String, keepCurrentTimeline: Boolean) {
        calibrationLoadJob?.cancel()
        calibrationLoadJob = viewModelScope.launch {
            if (keepCurrentTimeline) {
                _calibration.value = _calibration.value
                    ?.takeIf { it.followStartDate == followStartDate }
                    ?.copy(isLoading = true, error = null)
                    ?: TrackingCalibration(followStartDate = followStartDate)
            }
            val result = trackingRepository.getCalibratedTracking(followStartDate)
            val active = _calibration.value ?: return@launch
            if (active.followStartDate != followStartDate) return@launch
            result.fold(
                onSuccess = { response ->
                    val calibratedTimeline = response.toTimeline()
                    hydrateStockInfo(calibratedTimeline)
                    _calibration.value = active.copy(
                        timeline = calibratedTimeline,
                        isLoading = false,
                        error = null,
                    )
                },
                onFailure = { error ->
                    _calibration.value = active.copy(
                        isLoading = false,
                        error = error.message ?: "跟随校准视图加载失败",
                    )
                }
            )
        }
    }

    fun dismissDetail() {
        detailLoadJob?.cancel()
        _selectedStock.value = null
        _selectedDetail.value = null
    }

    fun selectStock(
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
        _selectedStock.value = selected
        _selectedDetail.value = SelectedStockDetail(
            selected = selected,
            stockInfo = stockInfoCache[node.stockCode],
            isLoading = true,
        )
        loadSelectedDetail(selected)
    }

    private fun observeStrategyPositionTracking() {
        viewModelScope.launch {
            GlobalWebSocketClient.strategyPositionTrackingFlow.collectLatest { response ->
                _isLoading.value = false
                if (response == null) {
                    _timeline.value = null
                    return@collectLatest
                }
                val timeline = response.toTimeline()
                hydrateStockInfo(timeline)
                _timeline.value = timeline
                rebuildSelectedDetail()
                // 模型流更新（如盘后新交易日落地）时，激活中的校准视图随之重放刷新
                _calibration.value?.let { loadCalibration(it.followStartDate, keepCurrentTimeline = true) }
            }
        }
        GlobalWebSocketClient.subscribeStrategyPositionTracking(STRATEGY_TRACKING_OWNER)
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
                    if (_selectedStock.value?.cardKey == selected.cardKey) {
                        _selectedDetail.value = _selectedDetail.value?.copy(
                            isLoading = false,
                            error = it.message ?: "K线数据加载失败"
                        )
                    }
                    return@launch
                }
                val stockInfo = infoResult.getOrNull()
                stockInfo?.let { stockInfoCache[it.code] = it }

                if (_selectedStock.value?.cardKey != selected.cardKey) {
                    return@launch
                }

                _selectedDetail.value = SelectedStockDetail(
                    selected = selected,
                    candleData = historicalCandleData,
                    stockInfo = stockInfo ?: stockInfoCache[selected.node.stockCode],
                    isLoading = false,
                    error = null,
                )
            } catch (e: Exception) {
                if (_selectedStock.value?.cardKey == selected.cardKey) {
                    _selectedDetail.value = _selectedDetail.value?.copy(
                        isLoading = false,
                        error = e.message ?: "加载失败"
                    )
                }
            }
        }
    }

    private fun rebuildSelectedDetail() {
        val current = _selectedDetail.value ?: return
        val timeline = _timeline.value ?: return

        val latestNode = timeline.days
            .flatMap { it.selection + it.holdings + it.cleared }
            .firstOrNull { it.stockCode == current.selected.node.stockCode && it.section == current.selected.section }

        if (latestNode != null && latestNode != current.selected.node) {
            _selectedDetail.value = current.copy(
                selected = current.selected.copy(node = latestNode)
            )
        }
    }

    private fun resolveStockName(code: String): String =
        stockInfoCache[code]?.name
            ?: _timeline.value?.days
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
        calibrationLoadJob?.cancel()
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

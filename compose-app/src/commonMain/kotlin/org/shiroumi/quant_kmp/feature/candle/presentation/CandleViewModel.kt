package org.shiroumi.quant_kmp.feature.candle.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import model.Candle
import model.CandleData
import model.candle.CandlePeriod
import model.candle.Exchange
import model.candle.CandleChartData
import model.candle.MarketStatus
import model.candle.StockInfo
import model.ws.CandleErrorCode
import org.shiroumi.quant_kmp.feature.candle.contract.CandleContract
import org.shiroumi.quant_kmp.feature.candle.contract.toCandleChartDataYielding
import org.shiroumi.quant_kmp.feature.candle.domain.repository.CandleRepository
import org.shiroumi.quant_kmp.service.CandleTraceLogger
import org.shiroumi.quant_kmp.service.ConnectionState
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.service.StockContextProvider
import org.shiroumi.config.AppConfig
import kotlin.uuid.ExperimentalUuidApi

private inline fun candleDebugLog(message: () -> String) {
    if (AppConfig.testMode) println(message())
}

private const val DEFAULT_CANDLE_WINDOW_LIMIT = 320

/**
 * 带上限的 LinkedHashSet 委托。
 *
 * add 时自动驱逐最早插入的条目，兼容 KMP WasmJS 不允许匿名继承 LinkedHashSet 的限制。
 */
private class BoundedLinkedSet<T>(private val maxSize: Int) {
    private val delegate = LinkedHashSet<T>()

    operator fun contains(element: T): Boolean = element in delegate

    fun add(element: T): Boolean {
        while (delegate.size >= maxSize) {
            delegate.remove(delegate.first())
        }
        return delegate.add(element)
    }
}

/**
 * 简易 LRU 缓存（委托 LinkedHashMap）。
 *
 * 兼容 KMP WasmJS 不允许匿名继承 LinkedHashMap 的限制。
 * get 时把命中的条目移到队尾（最近使用）；set 时在达到上限后驱逐队首。
 */
private class LruCache<K, V>(private val maxSize: Int) {
    private val delegate = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val value = delegate.remove(key) ?: return null
        delegate[key] = value // 移到队尾
        return value
    }

    operator fun set(key: K, value: V) {
        delegate.remove(key) // 移除旧位置（如果有）
        while (delegate.size >= maxSize) {
            delegate.remove(delegate.keys.first())
        }
        delegate[key] = value
    }
}

/**
 * 蜡烛图分析页面 ViewModel。
 *
 * 新的 K 线主链路已经切成 socket-first：
 * - 前端只订阅 `CANDLE_DATA`
 * - 后端负责维护完整快照并通过 websocket 推送
 * - ViewModel 不再自己拼接历史与实时数据
 */
class CandleViewModel(
    private val repository: CandleRepository
) : ViewModel() {
    private companion object {
        const val INTRADAY_SNAPSHOT_OWNER = "candle"
        const val STRATEGY_POSITIONS_OWNER = "candle"
        private val jsonForCandle = Json { ignoreUnknownKeys = true }

        // 切股 watchdog：8s 内若没有任何 CANDLE_DATA Data 或终态 Error，
        // 则结束 isLoadingCandle 并给出兜底错误。断线期间倒计时暂停，避免重连本身被算成超时。
        const val CANDLE_WATCHDOG_TIMEOUT_MS = 8_000L
        const val CANDLE_WATCHDOG_TICK_MS = 500L
    }

    private val _state = MutableStateFlow(CandleContract.State())
    val state: StateFlow<CandleContract.State> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<CandleContract.Effect>()
    val effect: SharedFlow<CandleContract.Effect> = _effect.asSharedFlow()

    /** 搜索防抖 Job */
    private var searchDebounceJob: Job? = null

    /** 当前选中股票与周期对应的 K 线订阅 Job */
    private var candleSubscriptionJob: Job? = null
    private var candleRetryJob: Job? = null
    private var candleWatchdogJob: Job? = null
    private var stockListPollingJob: Job? = null
    private var intradaySnapshotRetryJob: Job? = null
    private var strategyPositionsRetryJob: Job? = null
    private var screenLeaveCleanupJob: Job? = null
    private var latestIntradaySnapshotError: String? = null
    private var latestVisibleStockContext: List<String> = emptyList()
    private var activeCandleRequestSeq: Long = 0L
    private var activeStockListRequestSeq: Long = 0L
    // 行情页同一 viewModel 实例可被多个 NavEntry 同时持有（list pane + detail pane 并列，
    // 或快速切股时新 detail entry 已进入而旧 detail entry 还在 dispose 流程中）。
    // 用引用计数代替 boolean，避免新 entry 已经 enter 后被旧 entry 的 onScreenLeave 误清理。
    private var screenActiveRefCount: Int = 0
    private val isScreenActive: Boolean get() = screenActiveRefCount > 0
    /**
     * 已解析的策略选股代码集合，带上限防止无限增长。
     *
     * 每次新解析时 add，超过 256 后驱逐最早加入的条目。
     * 使用委托而非匿名继承，兼容 KMP WasmJS target（LinkedHashSet 在该目标为 final）。
     */
    private val resolvedPositionStockCodes = BoundedLinkedSet<String>(maxSize = 256)
    private val loadingPositionStockCodes = linkedSetOf<String>()

    // chartData LRU 缓存 (P1)
    // 指标计算是前端最重的同步操作（EMA/MA/RSI/MACD/BOLL），
    // 同股票同周期同版本数据重复进来时直接复用。
    // 使用委托而非匿名继承，兼容 KMP WasmJS target（LinkedHashMap 在该目标为 final）。
    private val chartDataCache = LruCache<String, CandleChartData>(maxSize = 16)

    init {
        loadStocks()
        loadSentimentHistory()
        observeMarketStatus()
        observeStockListPolling()
        observeIntradaySnapshot()
        observeStrategySelections()
        observeConnectionState()
    }

    /**
     * 把全局 WebSocket 连接状态投影到页面 State，
     * 让 UI 在断线/重连期间显示明确反馈而不是默默卡 loading。
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            GlobalWebSocketClient.connectionStateFlow.collect { state ->
                _state.value = _state.value.copy(connectionState = state)
            }
        }
    }

    /**
     * 行情页进入前台可见区域。
     *
     * 这里显式恢复页面级 topic 订阅，而不是等到 ViewModel 新建时被动初始化。
     * 原因是导航切页时 ViewModel 可能暂时仍然存活；如果只依赖 `onCleared`，
     * 页面离开后 topic 会继续留在服务端，直到 ViewModel 真正销毁才释放。
     */
    fun onScreenEnter() {
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = null
        val wasInactive = screenActiveRefCount == 0
        screenActiveRefCount += 1
        if (!wasInactive) return
        GlobalWebSocketClient.subscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
        GlobalWebSocketClient.subscribeStrategyPositions(STRATEGY_POSITIONS_OWNER)
        scheduleStrategyPositionsRetry()
        if (_state.value.selectedStock != null && candleSubscriptionJob?.isActive != true) {
            startCandleSubscription()
        }
        pushStockListContext()
    }

    /**
     * 行情页离开可见区域。
     *
     * 这里要立即撤销页面级订阅：
     * - K 线 topic：避免离场后继续收到 `CANDLE_DATA`
     * - 盘中快照 / 策略跟踪：避免页面不在前台时继续占用服务端订阅
     * - 股票列表上下文：避免服务端继续为不可见列表推送 `STOCK_LIST_UPDATE`
     */
    fun onScreenLeave() {
        if (screenActiveRefCount == 0) return
        screenActiveRefCount -= 1
        if (screenActiveRefCount > 0) return
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = viewModelScope.launch {
            delay(300)
            if (!isScreenActive) {
                performScreenLeaveCleanup()
            }
            screenLeaveCleanupJob = null
        }
    }

    private fun performScreenLeaveCleanup() {
        stopCandleSubscription()
        intradaySnapshotRetryJob?.cancel()
        intradaySnapshotRetryJob = null
        strategyPositionsRetryJob?.cancel()
        strategyPositionsRetryJob = null
        GlobalWebSocketClient.unsubscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
        GlobalWebSocketClient.unsubscribeStrategyPositions(STRATEGY_POSITIONS_OWNER)
        GlobalWebSocketClient.setStockListContext(emptyList())
    }

    /**
     * 处理用户动作
     */
    fun dispatch(action: CandleContract.Action) {
        when (action) {
            is CandleContract.Action.SelectStock -> selectStock(action.stock)
            is CandleContract.Action.UpdateSearchQuery -> updateSearchQuery(action.query)
            is CandleContract.Action.SelectExchange -> selectExchange(action.exchange)
            is CandleContract.Action.LoadMoreStocks -> loadMoreStocks()
            is CandleContract.Action.RefreshStocks -> refreshStocks()
            is CandleContract.Action.SelectPeriod -> selectPeriod(action.period)
            is CandleContract.Action.RefreshCandle -> refreshCandle()
            is CandleContract.Action.ToggleVolume -> toggleVolume(action.show)
            is CandleContract.Action.ToggleRsi -> toggleRsi(action.show)
            is CandleContract.Action.ToggleMacd -> toggleMacd(action.show)
            is CandleContract.Action.ToggleEma -> toggleEma(action.show)
            is CandleContract.Action.ToggleMa -> toggleMa(action.show)
            is CandleContract.Action.LoadStrategyData -> loadSentimentHistory()
            is CandleContract.Action.UpdateVisibleStocks -> updateStockListContext(action.tsCodes)
        }
    }

    private fun loadStocks(isSilent: Boolean = false) {
        activeStockListRequestSeq += 1L
        val requestSeq = activeStockListRequestSeq
        viewModelScope.launch {
            if (!isSilent) {
                _state.value = _state.value.copy(isLoadingStocks = true, stockListError = null)
            }
            try {
                val result = repository.getStocks(
                    page = _state.value.currentPage,
                    pageSize = 20,
                    exchange = _state.value.selectedExchange,
                    search = _state.value.searchQuery.takeIf { it.isNotBlank() }
                )

                result.onSuccess { response ->
                    if (requestSeq != activeStockListRequestSeq) {
                        return@onSuccess
                    }
                    // 合并策略选股股票：分页结果可能不包含策略选中的股票，
                    // 需要将之前已解析的策略股票保留在列表中
                    val responseCodeSet = response.stocks.map { it.code }.toHashSet()
                    val retainedStrategyStocks = _state.value.stocks.filter { stock ->
                        stock.code !in responseCodeSet &&
                            _state.value.strategySelectionCodes.contains(stock.code)
                    }
                    _state.value = _state.value.copy(
                        stocks = response.stocks + retainedStrategyStocks,
                        isLoadingStocks = false,
                        isSearching = false,
                        currentPage = response.pagination.page,
                        hasMoreStocks = response.pagination.hasNext
                    )
                    updateStockListContext(response.stocks.map { it.code })
                    // 优先恢复上次查看的股票；如果没有则默认选中第一只
                    if (response.stocks.isNotEmpty() && _state.value.selectedStock == null) {
                        val rememberedStock = StockContextProvider.selectedStock.value
                        val restoredStock = rememberedStock?.let { remembered ->
                            response.stocks.firstOrNull { it.code == remembered.code } ?: remembered
                        }
                        selectStock(restoredStock ?: response.stocks.first())
                    }
                    ensureStrategySelectionStocksLoaded()
                }.onFailure { error ->
                    if (requestSeq != activeStockListRequestSeq) {
                        return@onFailure
                    }
                    if (!isSilent) {
                        _state.value = _state.value.copy(
                            isLoadingStocks = false,
                            isSearching = false,
                            stockListError = error.message ?: "加载股票列表失败"
                        )
                        _effect.emit(CandleContract.Effect.ShowToast("加载股票列表失败: ${error.message}"))
                    }
                }
            } catch (e: Exception) {
                if (requestSeq != activeStockListRequestSeq) {
                    return@launch
                }
                if (!isSilent) {
                    _state.value = _state.value.copy(
                        isLoadingStocks = false,
                        isSearching = false,
                        stockListError = e.message ?: "未知错误"
                    )
                    _effect.emit(CandleContract.Effect.ShowToast("加载股票列表失败: ${e.message}"))
                }
            }
        }
    }

    private fun selectStock(stock: StockInfo) {
        val previousCode = _state.value.selectedStock?.code
        val previousPeriod = _state.value.selectedPeriod
        if (previousCode != null) {
            stopCandleSubscription(previousCode, previousPeriod)
        }
        _state.value = _state.value.copy(
            selectedStock = stock,
            candles = emptyList(),
            chartData = null,
            isLoadingCandle = true,
            candleError = null
        )
        StockContextProvider.updateSelectedStock(stock)
        startCandleSubscription()
    }

    /**
     * 启动 K 线快照订阅。
     *
     * 所有周期都统一走 `CANDLE_DATA`：
     * - 日线/分钟线不再单独订阅 realtime topic
     * - 周线/月线也不再回退到 HTTP
     */
    private fun startCandleSubscription() {
        val tsCode = _state.value.selectedStock?.code ?: return
        val period = _state.value.selectedPeriod
        activeCandleRequestSeq += 1L
        val requestSeq = activeCandleRequestSeq

        candleSubscriptionJob?.cancel()
        candleSubscriptionJob = null
        candleRetryJob?.cancel()
        candleRetryJob = null
        candleWatchdogJob?.cancel()
        candleWatchdogJob = null
        _state.value = _state.value.copy(
            isLoadingCandle = true,
            candleError = null
        )

        candleSubscriptionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            GlobalWebSocketClient.candleEventsFlow(tsCode, period).collectLatest { event ->
                when (event) {
                    is GlobalWebSocketClient.CandleStreamEvent.Data -> {
                        // 提前自校验当前选中股票，避免对已无人消费的 payload 做昂贵解码。
                        if (_state.value.selectedStock?.code != tsCode || _state.value.selectedPeriod != period) {
                            return@collectLatest
                        }
                        // 让出主线程，给 Compose 提交 loading 帧 + 让快速切股的取消信号生效。
                        yield()
                        val payload = runCatching { event.decode(jsonForCandle) }.getOrElse {
                            println("[CandleViewModel] Failed to decode CANDLE_DATA payload: ${it.message}")
                            return@collectLatest
                        }
                        yield()
                        val payloadSeq = payload.requestParams?.requestSeq
                        if (payloadSeq != null && payloadSeq != requestSeq) {
                            return@collectLatest
                        }
                        if (_state.value.selectedStock?.code != tsCode || _state.value.selectedPeriod != period) {
                            return@collectLatest
                        }
                        candleDebugLog {
                            "[CandleViewModel] Candle payload received tsCode=${payload.tsCode}, " +
                                "period=$period, count=${payload.totalCount}, requestSeq=$payloadSeq"
                        }
                        CandleTraceLogger.log(
                            stage = "STATE_APPLIED",
                            tsCode = payload.tsCode,
                            period = period,
                            requestSeq = payloadSeq,
                            detail = "count=${payload.totalCount}, selected=${_state.value.selectedStock?.code == tsCode}"
                        )
                        val candles = payload.candles.toCandleList(tsCode)
                        yield()
                        val cacheKey = buildString {
                            append(tsCode)
                            append(':')
                            append(period.name)
                            append(':')
                            append(payload.totalCount)
                            append(':')
                            append(candles.firstOrNull()?.date)
                            append(':')
                            append(candles.lastOrNull()?.date)
                        }
                        val chartData = chartDataCache[cacheKey] ?: computeChartData(candles)?.also {
                            chartDataCache[cacheKey] = it
                        }
                        yield()
                        if (_state.value.selectedStock?.code != tsCode || _state.value.selectedPeriod != period) {
                            return@collectLatest
                        }
                        // 数据成功到达：先 cancel watchdog 再更新 state，
                        // 避免 watchdog 在 state 已设为 isLoadingCandle=false 后多算一拍。
                        candleWatchdogJob?.cancel()
                        candleWatchdogJob = null
                        _state.value = _state.value.copy(
                            candles = candles,
                            chartData = chartData,
                            isLoadingCandle = false,
                            candleError = null
                        )
                    }
                    is GlobalWebSocketClient.CandleStreamEvent.Error -> {
                        if (event.payload.requestSeq != null && event.payload.requestSeq != requestSeq) {
                            return@collectLatest
                        }
                        if (_state.value.selectedStock?.code != tsCode || _state.value.selectedPeriod != period) {
                            return@collectLatest
                        }
                        if (event.payload.errorCode.isRetryable()) {
                            // 可恢复错误保持 loading：scheduleCandleRetry 自带退避封顶兜底，
                            // 让 watchdog 在 retry 流程中退出舞台，避免两套兜底相互抢断。
                            candleWatchdogJob?.cancel()
                            candleWatchdogJob = null
                            scheduleCandleRetry(tsCode, period)
                        } else {
                            candleWatchdogJob?.cancel()
                            candleWatchdogJob = null
                            _state.value = _state.value.copy(
                                isLoadingCandle = false,
                                candleError = event.payload.message
                            )
                        }
                    }
                }
            }
        }

        GlobalWebSocketClient.subscribeCandle(
            tsCode = tsCode,
            period = period,
            limit = DEFAULT_CANDLE_WINDOW_LIMIT,
            useAdjusted = true,
            requestSeq = requestSeq
        )

        resetCandleWatchdog(tsCode, period, requestSeq)
    }

    private fun observeMarketStatus() {
        viewModelScope.launch {
            GlobalWebSocketClient.marketStatusFlow.collectLatest { payload ->
                _state.value = _state.value.copy(marketStatus = payload.status)
            }
        }
    }

    /**
     * 更新搜索词并触发服务端搜索（防抖 400ms）
     */
    private fun updateSearchQuery(query: String) {
        searchDebounceJob?.cancel()
        _state.value = _state.value.copy(
            searchQuery = query,
            isSearching = query.isNotBlank()
        )
        searchDebounceJob = viewModelScope.launch {
            delay(180)
            // 每次搜索都从第1页开始，禁用加载更多
            _state.value = _state.value.copy(currentPage = 1)
            loadStocks(isSilent = false)
        }
    }

    private fun selectExchange(exchange: Exchange?) {
        _state.value = _state.value.copy(selectedExchange = exchange)
        // 重新加载股票列表
        refreshStocks()
    }

    private fun loadMoreStocks() {
        // 搜索模式下禁止加载更多
        if (_state.value.searchQuery.isNotBlank()) return
        if (_state.value.isLoadingMore || !_state.value.hasMoreStocks) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
            try {
                val nextPage = _state.value.currentPage + 1
                val result = repository.getStocks(
                    page = nextPage,
                    pageSize = 20,
                    exchange = _state.value.selectedExchange,
                    search = null  // loadMore 只在非搜索模式下使用
                )

                result.onSuccess { response ->
                    // 按 code 去重：列表尾部保留的策略选股（retainedStrategyStocks）可能在
                    // 后续分页里自然出现，直接相加会产生重复 code，触发 LazyColumn 重复 key 崩溃。
                    // distinctBy 保留先出现的元素，让策略股留在原尾部位置，避免顺序跳动。
                    val newStocks = (_state.value.stocks + response.stocks).distinctBy { it.code }
                    _state.value = _state.value.copy(
                        stocks = newStocks,
                        isLoadingMore = false,
                        currentPage = response.pagination.page,
                        hasMoreStocks = response.pagination.hasNext
                    )
                    ensureStrategySelectionStocksLoaded()
                }.onFailure { error ->
                    _state.value = _state.value.copy(isLoadingMore = false)
                    _effect.emit(CandleContract.Effect.ShowToast("加载更多股票失败: ${error.message}"))
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoadingMore = false)
                _effect.emit(CandleContract.Effect.ShowToast("加载更多股票失败: ${e.message}"))
            }
        }
    }

    private fun refreshStocks() {
        // 刷新时清空搜索词，重置到第1页
        searchDebounceJob?.cancel()
        _state.value = _state.value.copy(currentPage = 1, searchQuery = "", isSearching = false)
        loadStocks()
    }

    private fun selectPeriod(period: CandlePeriod) {
        val previousCode = _state.value.selectedStock?.code
        val previousPeriod = _state.value.selectedPeriod
        if (previousCode != null) {
            stopCandleSubscription(previousCode, previousPeriod)
        }
        _state.value = _state.value.copy(
            selectedPeriod = period,
            candles = emptyList(),
            chartData = null,
            isLoadingCandle = true,
            candleError = null
        )
        if (_state.value.selectedStock != null) {
            startCandleSubscription()
        }
    }

    private fun refreshCandle() {
        candleRetryJob?.cancel()
        candleRetryJob = null
        startCandleSubscription()
    }

    private fun toggleVolume(show: Boolean) {
        updateSubChartSelection(volume = show)
    }

    private fun toggleRsi(show: Boolean) {
        updateSubChartSelection(rsi = show)
    }

    private fun toggleMacd(show: Boolean) {
        updateSubChartSelection(macd = show)
    }

    private fun toggleEma(show: Boolean) {
        _state.value = _state.value.copy(showEma = show)
    }

    private fun toggleMa(show: Boolean) {
        _state.value = _state.value.copy(showMa = show)
    }

    private fun updateSubChartSelection(
        volume: Boolean? = null,
        rsi: Boolean? = null,
        macd: Boolean? = null
    ) {
        val current = _state.value
        val nextVolume = volume ?: current.showVolume
        val nextRsi = rsi ?: current.showRsi
        val nextMacd = macd ?: current.showMacd
        val activeCount = listOf(nextVolume, nextRsi, nextMacd).count { it }

        if (activeCount > 2) return

        _state.value = current.copy(
            showVolume = nextVolume,
            showRsi = nextRsi,
            showMacd = nextMacd
        )
    }

    /**
     * 停止当前 K 线订阅。
     */
    private fun stopCandleSubscription() {
        val tsCode = _state.value.selectedStock?.code ?: run {
            candleSubscriptionJob?.cancel()
            candleSubscriptionJob = null
            return
        }
        stopCandleSubscription(tsCode, _state.value.selectedPeriod)
    }

    /**
     * 按明确的股票代码与周期停止订阅。
     * 这样切股或切周期时可以先准确取消当前快照订阅，再更新当前状态。
     */
    private fun stopCandleSubscription(tsCode: String, period: CandlePeriod) {
        candleRetryJob?.cancel()
        candleRetryJob = null
        candleWatchdogJob?.cancel()
        candleWatchdogJob = null
        GlobalWebSocketClient.unsubscribeCandle(tsCode, period)
        candleSubscriptionJob?.cancel()
        candleSubscriptionJob = null
    }

    /**
     * 启动或重置切股 watchdog。
     *
     * 业务约束：
     * - 8s 窗口内必须收到任意一个 Data 或终态 Error，否则结束 loading 并给出兜底错误
     * - 断线期间（connectionState != CONNECTED）暂停倒计时，避免重连本身被误算成超时
     * - 用户切到其他股票/周期时 requestSeq 推进，自然让旧 watchdog 失效
     * - 只在 candleSubscriptionJob 仍 active 且 candles 仍为空时触发兜底
     */
    private fun resetCandleWatchdog(
        tsCode: String,
        period: CandlePeriod,
        requestSeq: Long
    ) {
        candleWatchdogJob?.cancel()
        candleWatchdogJob = viewModelScope.launch {
            var remainingMs = CANDLE_WATCHDOG_TIMEOUT_MS
            while (isActive && remainingMs > 0) {
                delay(CANDLE_WATCHDOG_TICK_MS)
                if (_state.value.selectedStock?.code != tsCode ||
                    _state.value.selectedPeriod != period ||
                    requestSeq != activeCandleRequestSeq
                ) {
                    return@launch
                }
                if (_state.value.candles.isNotEmpty()) {
                    return@launch
                }
                if (GlobalWebSocketClient.connectionStateFlow.value == ConnectionState.CONNECTED) {
                    remainingMs -= CANDLE_WATCHDOG_TICK_MS
                }
            }
            // 仅在仍处于"无数据且无真实错误"的状态下兜底，
            // 避免覆盖 Error 分支已经设置的具体错误信息。
            if (_state.value.candles.isEmpty() &&
                _state.value.candleError == null &&
                _state.value.selectedStock?.code == tsCode &&
                _state.value.selectedPeriod == period &&
                requestSeq == activeCandleRequestSeq
            ) {
                _state.value = _state.value.copy(
                    isLoadingCandle = false,
                    candleError = "K线加载超时，请检查网络后重试"
                )
            }
        }
    }

    /**
     * 对启动预热类错误执行指数退避自动重试。
     *
     * 硬约束：
     * 1. 只能对”系统尚未 ready”这类明确可恢复错误重试
     * 2. 重试期间如果用户切换股票/周期，旧重试必须立即失效
     * 3. 退避封顶后若仍无数据，结束 loading 避免无限等待
     */
    private fun scheduleCandleRetry(
        tsCode: String,
        period: CandlePeriod
    ) {
        candleRetryJob?.cancel()
        candleRetryJob = viewModelScope.launch {
            var delayMs = 1_000L
            val maxDelayMs = 30_000L
            while (isActive) {
                delay(delayMs)
                val currentCode = _state.value.selectedStock?.code
                val currentPeriod = _state.value.selectedPeriod
                if (currentCode != tsCode || currentPeriod != period) {
                    return@launch
                }
                // 如果数据已经在重试间隙成功到达，直接结束
                if (_state.value.candles.isNotEmpty()) {
                    return@launch
                }
                GlobalWebSocketClient.subscribeCandle(
                    tsCode = tsCode,
                    period = period,
                    limit = DEFAULT_CANDLE_WINDOW_LIMIT,
                    useAdjusted = true,
                    requestSeq = activeCandleRequestSeq
                )
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                // 退避已达上限且再次等待后仍无数据，结束 loading 并给出兜底提示
                if (delayMs == maxDelayMs) {
                    if (_state.value.selectedStock?.code == tsCode &&
                        _state.value.selectedPeriod == period &&
                        _state.value.candles.isEmpty()
                    ) {
                        _state.value = _state.value.copy(
                            isLoadingCandle = false,
                            candleError = "K线数据加载超时，请稍后重试"
                        )
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * 开启股票列表实时行情更新监听
     *
     * 当前股票列表实时更新只认 `STOCK_LIST_UPDATE`：
     * - 服务端根据页面上下文推送轻量报价
     * - ViewModel 只消费推送结果
     * - 不再自行做任何定时拉取或旧链路恢复
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun observeStockListPolling() {
        stockListPollingJob?.cancel()
        stockListPollingJob = viewModelScope.launch {
            GlobalWebSocketClient.stockListUpdateFlow.collect { payload ->
                candleDebugLog { "[CandleViewModel] Received real-time updates for ${payload.stocks.size} stocks" }
                
                val currentStocks = _state.value.stocks.toMutableList()
                var listChanged = false
                var selectedStockUpdated: StockInfo? = null

                payload.stocks.forEach { update ->
                    val index = currentStocks.indexOfFirst { it.code == update.code }
                    if (index != -1) {
                        val original = currentStocks[index]
                        val updated = original.copy(
                            latestPrice = update.latestPrice,
                            changePercent = update.changePercent
                        )
                        currentStocks[index] = updated
                        listChanged = true
                        
                        if (_state.value.selectedStock?.code == update.code) {
                            selectedStockUpdated = updated
                        }
                    }
                }

                if (listChanged) {
                    _state.value = _state.value.copy(
                        stocks = currentStocks,
                        selectedStock = selectedStockUpdated ?: _state.value.selectedStock
                    )
                }
            }
        }
    }

    /**
     * 监听盘中快照流，驱动服务端产生最新策略持仓状态。
     * 行情页的“策略选股”列表只消费轻量的 STRATEGY_POSITIONS，
     * 不再依赖策略跟踪页的调入/持仓/清仓时间线快照。
     */
    private fun observeIntradaySnapshot() {
        viewModelScope.launch {
            GlobalWebSocketClient.intradaySnapshotFlow.collect { snapshot ->
                snapshot?.let {
                    latestIntradaySnapshotError = null
                    intradaySnapshotRetryJob?.cancel()
                    intradaySnapshotRetryJob = null
                    pushStockListContext()
                }
            }
        }
        viewModelScope.launch {
            GlobalWebSocketClient.intradaySnapshotErrorFlow.collect { errorMessage ->
                latestIntradaySnapshotError = errorMessage
                if (errorMessage.isRetryableIntradaySnapshotError()) {
                    scheduleIntradaySnapshotRetry()
                }
            }
        }
    }

    private fun scheduleIntradaySnapshotRetry() {
        if (!isScreenActive) return
        if (intradaySnapshotRetryJob?.isActive == true) return
        intradaySnapshotRetryJob = viewModelScope.launch {
            var delayMs = 1_000L
            val maxDelayMs = 30_000L
            while (isActive && latestIntradaySnapshotError.isRetryableIntradaySnapshotError()) {
                delay(delayMs)
                if (!latestIntradaySnapshotError.isRetryableIntradaySnapshotError()) {
                    break
                }
                GlobalWebSocketClient.subscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
            intradaySnapshotRetryJob = null
        }
    }

    /**
     * 更新当前视口可见股票上下文
     */
    private fun updateStockListContext(tsCodes: List<String>) {
        latestVisibleStockContext = tsCodes
        pushStockListContext()
    }

    private fun pushStockListContext() {
        if (!isScreenActive) return
        val contextCodes = (latestVisibleStockContext + _state.value.strategySelectionCodes)
            .filter { it.isNotBlank() }
            .distinct()
        candleDebugLog { "[CandleViewModel] UI stock list context changed: $contextCodes" }
        GlobalWebSocketClient.setStockListContext(contextCodes)
    }

    /**
     * 根据 candles 和当前选中股票计算图表数据（带 yield）。
     *
     * 指标计算开销较大（EMA/MA/RSI/MACD/BOLL），仅在 candles 真正变化时调用。
     * 使用 yielding 版本在各指标组之间让出主线程，避免 Compose 掉帧。
     */
    private suspend fun computeChartData(candles: List<Candle>): CandleChartData? {
        val stock = _state.value.selectedStock ?: return null
        if (candles.isEmpty()) return null
        return candles.toCandleChartDataYielding(stock.code, stock.name)
    }

    private fun loadSentimentHistory() {
        viewModelScope.launch {
            try {
                val sentimentResult = repository.getStrategySentiment(limit = 60)
                val sentiment = sentimentResult.getOrNull() ?: emptyList()
                _state.value = _state.value.copy(sentimentHistory = sentiment)
            } catch (_: Exception) { }
        }
    }

    private fun observeStrategySelections() {
        viewModelScope.launch {
            GlobalWebSocketClient.strategyPositionsFlow.collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                strategyPositionsRetryJob?.cancel()
                strategyPositionsRetryJob = null
                val latestSelectionCodes = snapshot.nextSessionSelections

                _state.value = _state.value.copy(
                    strategySelectionCodes = latestSelectionCodes,
                    isStrategySelectionReady = true
                )
                pushStockListContext()
                ensureStrategySelectionStocksLoaded()
            }
        }
    }

    private fun scheduleStrategyPositionsRetry() {
        if (!isScreenActive) return
        if (strategyPositionsRetryJob?.isActive == true) return
        strategyPositionsRetryJob = viewModelScope.launch {
            var delayMs = 1_000L
            val maxDelayMs = 30_000L
            while (isActive && !_state.value.isStrategySelectionReady) {
                delay(delayMs)
                if (_state.value.isStrategySelectionReady) {
                    break
                }
                GlobalWebSocketClient.subscribeStrategyPositions(STRATEGY_POSITIONS_OWNER)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
            strategyPositionsRetryJob = null
        }
    }

    /**
     * 补齐策略选股对应的股票详情。
     *
     * 首页“策略选股”Tab 直接从当前分页股票里做过滤，
     * 一旦选股股票不在当前页，就会错误显示为空。
     * 这里按选股代码补齐缺失的 StockInfo，并合并回状态列表。
     */
    private fun ensureStrategySelectionStocksLoaded() {
        val selectionCodes = _state.value.strategySelectionCodes.distinct()
        if (selectionCodes.isEmpty()) return

        // 预先构建已知股票代码集合，避免 selectionCodes × stocks 的 O(n*m) 扫描
        val knownCodes = _state.value.stocks.mapTo(HashSet(_state.value.stocks.size)) { it.code }
        val missingCodes = selectionCodes.filterNot { code ->
            code in knownCodes ||
                resolvedPositionStockCodes.contains(code) ||
                loadingPositionStockCodes.contains(code)
        }
        if (missingCodes.isEmpty()) return

        loadingPositionStockCodes.addAll(missingCodes)
        viewModelScope.launch {
            try {
                val fetchedStocks = missingCodes.map { code ->
                    async { repository.getStockByCode(code).getOrNull() }
                }.awaitAll().filterNotNull()

                if (fetchedStocks.isEmpty()) return@launch

                val mergedByCode = LinkedHashMap<String, StockInfo>()
                _state.value.stocks.forEach { stock ->
                    mergedByCode[stock.code] = stock
                }
                fetchedStocks.forEach { stock ->
                    mergedByCode[stock.code] = stock
                    resolvedPositionStockCodes.add(stock.code)
                }

                _state.value = _state.value.copy(stocks = mergedByCode.values.toList())
            } finally {
                loadingPositionStockCodes.removeAll(missingCodes.toSet())
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        screenActiveRefCount = 0
        screenLeaveCleanupJob?.cancel()
        screenLeaveCleanupJob = null
        performScreenLeaveCleanup()
        candleRetryJob?.cancel()
        candleWatchdogJob?.cancel()
        candleWatchdogJob = null
        stockListPollingJob?.cancel()
    }
}

private fun CandleErrorCode.isRetryable(): Boolean = when (this) {
    CandleErrorCode.SYSTEM_WARMING_UP,
    CandleErrorCode.PROVIDER_NOT_READY -> true
    else -> false
}

private fun String?.isRetryableIntradaySnapshotError(): Boolean {
    if (this.isNullOrBlank()) return false
    return contains("初始化") || contains("未就绪") || contains("准备")
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun List<CandleData>.toCandleList(tsCode: String): List<Candle> {
    // 500 根日线时，LocalDate.parse 在主线程上有几毫秒固定开销。
    // 走 LocalDate(year, month, day) 直接构造比走 ISO 解析快若干倍，
    // 在快速切股场景下能从主线程上腾出关键几帧。
    val fallback = kotlinx.datetime.LocalDate(1970, 1, 1)
    return map { cd ->
        Candle(
            tsCode = tsCode,
            date = parseLocalDateFast(cd.date) ?: fallback,
            tradeTime = cd.date,
            open = cd.open, high = cd.high, low = cd.low, close = cd.close,
            adj = 1f,
            openQfq = cd.adjOpen ?: cd.open,
            closeQfq = cd.adjClose ?: cd.close,
            highQfq = cd.adjHigh ?: cd.high,
            lowQfq = cd.adjLow ?: cd.low,
            volume = cd.volume,
            volumeQfq = cd.volume,
            turnoverReal = cd.turnover,
            pe = 0f, peTtm = 0f, pb = 0f, ps = 0f, psTtm = 0f,
            mvTotal = 0f, mvCirc = 0f
        )
    }
}

/**
 * 极快路径解析 "YYYY-MM-DD" 或 "YYYY-MM-DD HH:mm:ss"。
 * 避免 LocalDate.parse 触发的字符串校验、ISO 状态机和异常包装。
 */
private fun parseLocalDateFast(raw: String): kotlinx.datetime.LocalDate? {
    if (raw.length < 10) return null
    val y0 = raw[0].code - '0'.code
    val y1 = raw[1].code - '0'.code
    val y2 = raw[2].code - '0'.code
    val y3 = raw[3].code - '0'.code
    val m0 = raw[5].code - '0'.code
    val m1 = raw[6].code - '0'.code
    val d0 = raw[8].code - '0'.code
    val d1 = raw[9].code - '0'.code
    if (y0 or y1 or y2 or y3 or m0 or m1 or d0 or d1 < 0) return null
    if (y0 or y1 or y2 or y3 > 9 || m0 > 9 || m1 > 9 || d0 > 9 || d1 > 9) return null
    val year = y0 * 1000 + y1 * 100 + y2 * 10 + y3
    val month = m0 * 10 + m1
    val day = d0 * 10 + d1
    if (month !in 1..12 || day !in 1..31) return null
    return runCatching { kotlinx.datetime.LocalDate(year, month, day) }.getOrNull()
}

package org.shiroumi.quant_kmp.feature.candle.contract

import model.Candle
import model.candle.*
import org.shiroumi.quant_kmp.service.ConnectionState
import org.shiroumi.quant_kmp.ui.core.mvi.UiAction
import org.shiroumi.quant_kmp.ui.core.mvi.UiEffect
import org.shiroumi.quant_kmp.ui.core.mvi.UiState

/**
 * 蜡烛图分析页面 MVI 契约
 */
object CandleContract {

    /**
     * 页面状态
     */
    data class State(
        // 股票列表
        val stocks: List<StockInfo> = emptyList(),
        val selectedStock: StockInfo? = null,
        val isLoadingStocks: Boolean = false,
        val stockListError: String? = null,

        // 蜡烛图数据 - 使用新的Candle模型
        val candles: List<Candle> = emptyList(),
        val isLoadingCandle: Boolean = false,
        val candleError: String? = null,

        // 搜索与筛选
        val searchQuery: String = "",
        val selectedPeriod: CandlePeriod = CandlePeriod.DAY,
        val selectedExchange: Exchange? = null,

        // 图表配置
        val showVolume: Boolean = true,
        val showRsi: Boolean = true,
        val showMacd: Boolean = false,
        val showEma: Boolean = true,
        val showMa: Boolean = false,

        // 分页
        val currentPage: Int = 1,
        val hasMoreStocks: Boolean = true,
        val isLoadingMore: Boolean = false,

        // 搜索防抖状态（输入中，尚未发出请求时显示加载中）
        val isSearching: Boolean = false,

        // 策略情绪与选股
        val strategySelectionCodes: List<String> = emptyList(),
        val sentimentHistory: List<StrategySentimentResponse> = emptyList(),
        val isLoadingStrategy: Boolean = false,
        val isStrategySelectionReady: Boolean = false,
        val marketStatus: MarketStatus = MarketStatus.CLOSED,

        // 预计算图表数据，避免 UI 每次读取时重复计算指标
        val chartData: CandleChartData? = null,

        /**
         * 全局 WebSocket 连接状态。
         *
         * 用于在 isLoadingCandle 期间区分"普通骨架屏"与"网络重连中"两类等待，
         * 避免断线时订阅命令被 isRestorableStateCommand 静默丢弃后 UI 长时间无声 loading。
         */
        val connectionState: ConnectionState = ConnectionState.CONNECTED,
    ) : UiState {
        /**
         * 筛选后的股票列表。
         * 搜索已由服务端完成，直接返回 stocks 即可。
         */
        val filteredStocks: List<StockInfo>
            get() = stocks

        /** 列表或 K 线任一在加载即视为加载中。 */
        override val isLoading: Boolean
            get() = isLoadingCandle || isLoadingStocks

        /** K 线错误优先于列表错误展示。 */
        override val errorMessage: String?
            get() = candleError ?: stockListError
    }

    /**
     * 用户动作
     */
    sealed class Action : UiAction {
        // 股票列表操作
        data class SelectStock(val stock: StockInfo) : Action()
        data class UpdateSearchQuery(val query: String) : Action()
        data class SelectExchange(val exchange: Exchange?) : Action()
        data object LoadMoreStocks : Action()
        data object RefreshStocks : Action()
        data object LoadStrategyData : Action()

        // 蜡烛图数据操作
        data class SelectPeriod(val period: CandlePeriod) : Action()
        data object RefreshCandle : Action()

        data class UpdateVisibleStocks(val tsCodes: List<String>) : Action()

        // 图表配置操作
        data class ToggleVolume(val show: Boolean) : Action()
        data class ToggleRsi(val show: Boolean) : Action()
        data class ToggleMacd(val show: Boolean) : Action()
        data class ToggleEma(val show: Boolean) : Action()
        data class ToggleMa(val show: Boolean) : Action()
    }

    /**
     * 副作用
     */
    sealed class Effect : UiEffect {
        data class ShowToast(val message: String) : Effect()
        data class NavigateToStockDetail(val code: String) : Effect()
    }
}

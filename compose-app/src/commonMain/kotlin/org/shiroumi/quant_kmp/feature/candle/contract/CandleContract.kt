package org.shiroumi.quant_kmp.feature.candle.contract

import kotlinx.coroutines.yield
import model.Candle
import model.candle.*
import org.shiroumi.quant_kmp.feature.candle.domain.IndicatorCalculator
import org.shiroumi.quant_kmp.service.ConnectionState

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
    ) {
        /**
         * 筛选后的股票列表。
         * 搜索已由服务端完成，直接返回 stocks 即可。
         */
        val filteredStocks: List<StockInfo>
            get() = stocks
    }

    /**
     * 用户动作
     */
    sealed class Action {
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
    sealed class Effect {
        data class ShowToast(val message: String) : Effect()
        data class NavigateToStockDetail(val code: String) : Effect()
    }
}

/**
 * 将Candle列表转换为CandleChartData
 */
fun List<Candle>.toCandleChartData(code: String, name: String): CandleChartData {
    if (isEmpty()) {
        return CandleChartData(
            code = code,
            name = name,
            candles = emptyList(),
            volumes = emptyList(),
            ema20 = emptyList(),
            rsi6 = emptyList(),
            macdDif = emptyList(),
            macdDea = emptyList(),
            macdBar = emptyList()
        )
    }

    // 转换为CandleData用于UI展示
    // 分钟线优先使用 tradeTime（完整时间戳），日线使用 date 字符串
    val candleDataList = mapIndexed { index, candle ->
        CandleData(
            date = candle.tradeTime ?: candle.date.toString(),
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume,
            turnover = candle.turnoverReal,
            changePercent = if (index > 0) {
                val prev = get(index - 1)
                if (prev.close != 0f) ((candle.close - prev.close) / prev.close * 100) else null
            } else null
        )
    }

    // 计算技术指标
    val closes = map { it.close.toDouble() }
    val volumes = map { it.volume }

    // EMA指标
    val ema5Values = calculateEMAList(closes, 5)
    val ema10Values = calculateEMAList(closes, 10)
    val ema20Values = calculateEMAList(closes, 20)
    val ema60Values = calculateEMAList(closes, 60)

    // MA指标
    val ma5Values = calculateSMAList(closes, 5)
    val ma10Values = calculateSMAList(closes, 10)
    val ma20Values = calculateSMAList(closes, 20)
    val ma60Values = calculateSMAList(closes, 60)

    // RSI指标
    val rsi6Values = calculateRSIList(closes, 6)
    val rsi12Values = calculateRSIList(closes, 12)
    val rsi24Values = calculateRSIList(closes, 24)

    // MACD指标
    val (macdDifValues, macdDeaValues, macdBarValues) = calculateMACDList(closes)

    // 布林带
    val (bollUpperValues, bollMidValues, bollLowerValues) = calculateBollingerBandsList(closes, 20, 2.0)

    return CandleChartData(
        code = code,
        name = name,
        candles = candleDataList,
        volumes = volumes,
        ema20 = ema20Values,
        rsi6 = rsi6Values,
        macdDif = macdDifValues,
        macdDea = macdDeaValues,
        macdBar = macdBarValues,
        // 扩展指标
        ema5 = ema5Values,
        ema10 = ema10Values,
        ema60 = ema60Values,
        rsi12 = rsi12Values,
        rsi24 = rsi24Values,
        ma5 = ma5Values,
        ma10 = ma10Values,
        ma20 = ma20Values,
        ma60 = ma60Values,
        // 布林带
        bollUpper = bollUpperValues,
        bollMid = bollMidValues,
        bollLower = bollLowerValues
    )
}

/**
 * 将 Candle 列表转换为 CandleChartData（带 yield）
 *
 * 与 [toCandleChartData] 相同，但在每类指标计算之间插入 yield()，
 * 让 Compose 有机会提交中间帧，避免主线程一次性算完 500 根 K 线的
 * EMA×4 + MA×4 + RSI×3 + MACD + BOLL 时掉帧。
 */
suspend fun List<Candle>.toCandleChartDataYielding(code: String, name: String): CandleChartData {
    if (isEmpty()) {
        return CandleChartData(
            code = code, name = name,
            candles = emptyList(), volumes = emptyList(),
            ema20 = emptyList(), rsi6 = emptyList(),
            macdDif = emptyList(), macdDea = emptyList(), macdBar = emptyList()
        )
    }

    val candleDataList = mapIndexed { index, candle ->
        CandleData(
            date = candle.tradeTime ?: candle.date.toString(),
            open = candle.open, high = candle.high, low = candle.low, close = candle.close,
            volume = candle.volume, turnover = candle.turnoverReal,
            changePercent = if (index > 0) {
                val prev = get(index - 1)
                if (prev.close != 0f) ((candle.close - prev.close) / prev.close * 100) else null
            } else null
        )
    }

    val closes = map { it.close.toDouble() }
    val volumes = map { it.volume }

    // EMA
    val ema5Values = calculateEMAList(closes, 5)
    val ema10Values = calculateEMAList(closes, 10)
    val ema20Values = calculateEMAList(closes, 20)
    val ema60Values = calculateEMAList(closes, 60)
    yield()

    // MA
    val ma5Values = calculateSMAList(closes, 5)
    val ma10Values = calculateSMAList(closes, 10)
    val ma20Values = calculateSMAList(closes, 20)
    val ma60Values = calculateSMAList(closes, 60)
    yield()

    // RSI
    val rsi6Values = calculateRSIList(closes, 6)
    val rsi12Values = calculateRSIList(closes, 12)
    val rsi24Values = calculateRSIList(closes, 24)
    yield()

    // MACD
    val (macdDifValues, macdDeaValues, macdBarValues) = calculateMACDList(closes)
    yield()

    // 布林带
    val (bollUpperValues, bollMidValues, bollLowerValues) = calculateBollingerBandsList(closes, 20, 2.0)
    yield()

    return CandleChartData(
        code = code, name = name,
        candles = candleDataList, volumes = volumes,
        ema20 = ema20Values, rsi6 = rsi6Values,
        macdDif = macdDifValues, macdDea = macdDeaValues, macdBar = macdBarValues,
        ema5 = ema5Values, ema10 = ema10Values, ema60 = ema60Values,
        rsi12 = rsi12Values, rsi24 = rsi24Values,
        ma5 = ma5Values, ma10 = ma10Values, ma20 = ma20Values, ma60 = ma60Values,
        bollUpper = bollUpperValues, bollMid = bollMidValues, bollLower = bollLowerValues
    )
}

// ==================== 技术指标计算函数委托 ====================
// 所有指标计算已迁移至 IndicatorCalculator 对象

/**
 * 计算简单移动平均列表
 * @deprecated 使用 IndicatorCalculator.calculateSMAList 替代
 */
private fun calculateSMAList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateSMAList(data, period)

/**
 * 计算指数移动平均列表
 * @deprecated 使用 IndicatorCalculator.calculateEMAList 替代
 */
private fun calculateEMAList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateEMAList(data, period)

/**
 * 计算RSI列表
 * @deprecated 使用 IndicatorCalculator.calculateRSIList 替代
 */
private fun calculateRSIList(data: List<Double>, period: Int): List<Float?> =
    IndicatorCalculator.calculateRSIList(data, period)

/**
 * 计算MACD列表
 * 返回 Triple(DIF列表, DEA列表, BAR列表)
 * @deprecated 使用 IndicatorCalculator.calculateMACDList 替代
 */
private fun calculateMACDList(
    data: List<Double>,
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): Triple<List<Float?>, List<Float?>, List<Float?>> =
    IndicatorCalculator.calculateMACDList(data, fastPeriod, slowPeriod, signalPeriod)

/**
 * 计算布林带列表
 * 返回 Triple(上轨列表, 中轨列表, 下轨列表)
 * @deprecated 使用 IndicatorCalculator.calculateBollingerBandsList 替代
 */
private fun calculateBollingerBandsList(
    data: List<Double>,
    period: Int = 20,
    multiplier: Double = 2.0
): Triple<List<Float?>, List<Float?>, List<Float?>> =
    IndicatorCalculator.calculateBollingerBandsList(data, period, multiplier)

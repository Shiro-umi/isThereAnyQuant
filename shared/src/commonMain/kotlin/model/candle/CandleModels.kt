package model.candle

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 股票信息
 * 包含股票基本信息和实时行情
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class StockInfo(
    val id: Uuid = Uuid.random(),
    val code: String,
    val name: String,
    val exchange: Exchange,
    val industry: String,
    val sector: String,
    val latestPrice: Float,
    val changePercent: Float,
    val changeAmount: Float,
    val volume: Float,
    val turnover: Float,
    val marketCap: Float,
    val peRatio: Float? = null,
    val pbRatio: Float? = null,
    val dayHigh: Float,
    val dayLow: Float,
    val openPrice: Float,
    val prevClose: Float,
    val updateTime: Long
) {
    /**
     * 股票代码完整格式（带交易所后缀）
     */
    val fullCode: String
        get() = if (code.contains(".")) {
            code
        } else when (exchange) {
            Exchange.SH -> "$code.SH"
            Exchange.SZ -> "$code.SZ"
            Exchange.BJ -> "$code.BJ"
            Exchange.HK -> "$code.HK"
            Exchange.US -> code
        }
}

/**
 * 交易所枚举
 */
@Serializable
enum class Exchange {
    SH,     // 上海证券交易所
    SZ,     // 深圳证券交易所
    BJ,     // 北京证券交易所
    HK,     // 香港交易所
    US      // 美股
}

/**
 * K线周期枚举
 */
@Serializable
enum class CandlePeriod {
    MIN_5,      // 5分钟
    MIN_15,     // 15分钟
    MIN_30,     // 30分钟
    MIN_60,     // 60分钟
    DAY,        // 日线
    WEEK,       // 周线
    MONTH       // 月线
}

/**
 * 技术指标配置
 */
@Serializable
data class TechnicalIndicatorConfig(
    val emaPeriods: List<Int> = listOf(5, 10, 20, 60),
    val rsiPeriods: List<Int> = listOf(6, 12, 24),
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val maPeriods: List<Int> = listOf(5, 10, 20, 60)
)

/**
 * K线数据查询请求
 */
@Serializable
data class CandleQueryRequest(
    val code: String,
    val period: CandlePeriod = CandlePeriod.DAY,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val limit: Int = 100
)

/**
 * K线数据查询响应
 */
@Serializable
data class CandleQueryResponse(
    val code: String,
    val name: String,
    val period: CandlePeriod,
    val data: StockKLineInfo?,
    val error: String? = null
)

/**
 * 分页信息
 * 用于列表查询响应的分页元数据
 */
@Serializable
data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

/**
 * 股票列表查询响应
 */
@Serializable
data class StockListResponse(
    val stocks: List<StockInfo>,
    val pagination: PaginationInfo
)

@Serializable
data class StrategySentimentResponse(
    val tradeDate: String,
    val sentimentExposure: Double,
    val bullRatio: Double,
    val marketVol: Double,
    val fftScore: Double,
    val residualScore: Double,
    val accelZ: Double,
    val volZ: Double,
    val selectedCount: Int,
    val emptyReason: String? = null,
    val ratioNorm: Double = 0.0,
    val volScore: Double = 0.0,
    val accelScore: Double = 0.0,
    val absoluteFloor: Double = 0.0,
    val volCap: Double = 0.0,
)

@Serializable
enum class StrategyTrackingSection {
    SELECTION,
    HOLDINGS,
    CLEARED,
}

/** 持仓状态机离场原因，与 strategy-service HoldingStateMachine 的退出优先级一一对应。 */
@Serializable
enum class StrategyTrackingExitReason {
    TAKE_PROFIT,
    PROFIT_PROTECT,
    TIME_STOP,
    PRICE_STOP,
}

@Serializable
data class StrategyTrackingStockNode(
    val stockCode: String,
    val stockName: String,
    val section: StrategyTrackingSection,
    val slotIndex: Int,
    val modelScore: Double? = null,
    val buyDate: String? = null,
    val buyPrice: Float? = null,
    val actualPnl: Float? = null,
    val maxPnl: Float? = null,
    /** 观察日价格：历史日为收盘价，盘中实时日为最新价。 */
    val currentPrice: Float? = null,
    /** 观察日当日涨跌幅 %，选股节点填充，用于判断次日入场的跳空空间。 */
    val dayChangePct: Float? = null,
    /** 清仓节点：状态机离场原因，服务端按生产持仓规则重建。 */
    val exitReason: StrategyTrackingExitReason? = null,
    /** 清仓节点：规则口径已实现收益 %（止盈/保盈按触价、到期按收盘）。 */
    val exitPnl: Float? = null,
)

/** 流转边类型：跨日持有主干 / 选股→次日买入 / 持有→清仓。 */
@Serializable
enum class StrategyTrackingEdgeKind {
    HOLD_CONTINUE,
    ENTER_HOLDING,
    EXIT_CLEAR,
}

/**
 * 持仓跟踪时间线流转边，由 strategy-service 云端计算，前端只负责渲染。
 * [pnlPct] 语义按 [kind] 区分：HOLD_CONTINUE = 目标日当日涨跌幅；
 * ENTER_HOLDING = 入场日开盘→收盘涨跌幅；EXIT_CLEAR = 规则口径已实现收益。
 */
@Serializable
data class StrategyTrackingEdge(
    val fromDate: String,
    val fromSection: StrategyTrackingSection,
    val fromStockCode: String,
    val fromSlotIndex: Int,
    val toDate: String,
    val toSection: StrategyTrackingSection,
    val toStockCode: String,
    val toSlotIndex: Int,
    val kind: StrategyTrackingEdgeKind,
    val pnlPct: Float? = null,
    val exitReason: StrategyTrackingExitReason? = null,
)

@Serializable
data class StrategyPositionTrackingDay(
    val tradeDate: String,
    val selection: List<StrategyTrackingStockNode>,
    val holdings: List<StrategyTrackingStockNode>,
    val cleared: List<StrategyTrackingStockNode>,
)

@Serializable
data class StrategyPositionTrackingResponse(
    val days: List<StrategyPositionTrackingDay>,
    /** 跨日流转边（含盈亏百分比），服务端计算产物。 */
    val edges: List<StrategyTrackingEdge> = emptyList(),
    /** 最后一日为盘中实时投影时的交易日；null 表示全部为确认交易日。 */
    val realtimeTradeDate: String? = null,
)

/**
 * 股票筛选条件
 */
@Serializable
data class StockFilterCriteria(
    val exchange: Exchange? = null,
    val industry: String? = null,
    val minPrice: Float? = null,
    val maxPrice: Float? = null,
    val minChangePercent: Float? = null,
    val maxChangePercent: Float? = null,
    val minMarketCap: Float? = null,
    val maxMarketCap: Float? = null
)

/**
 * 行业分类
 */
@Serializable
enum class IndustryCategory(val displayName: String) {
    TECHNOLOGY("科技"),
    FINANCE("金融"),
    HEALTHCARE("医药"),
    CONSUMER("消费"),
    ENERGY("能源"),
    MATERIALS("材料"),
    INDUSTRIALS("工业"),
    UTILITIES("公用事业"),
    REAL_ESTATE("房地产"),
    TELECOM("通信"),
    AUTOMOTIVE("汽车"),
    MEDIA("传媒"),
    AGRICULTURE("农业"),
    CHEMICAL("化工"),
    ELECTRONICS("电子")
}

/**
 * 市场状态
 */
@Serializable
enum class MarketStatus {
    PRE_MARKET,     // 盘前
    OPEN,           // 交易中
    CLOSED,         // 收盘
    POST_MARKET     // 盘后
}

/**
 * 实时行情推送数据
 */
@Serializable
data class RealTimeQuote(
    val code: String,
    val name: String,
    val price: Float,
    val changePercent: Float,
    val changeAmount: Float,
    val volume: Float,
    val turnover: Float,
    val timestamp: Long,
    val bidPrice: Float? = null,
    val askPrice: Float? = null,
    val bidVolume: Float? = null,
    val askVolume: Float? = null
)

/**
 * K线图表数据（用于UI展示）
 * 与现有StockChartData兼容
 */
@Serializable
data class CandleChartData(
    val code: String,
    val name: String,
    val candles: List<CandleData>,
    val volumes: List<Float>,
    val ema20: List<Float?>,
    val rsi6: List<Float?>,
    val macdDif: List<Float?>,
    val macdDea: List<Float?>,
    val macdBar: List<Float?>,
    // 扩展指标
    val ema5: List<Float?>? = null,
    val ema10: List<Float?>? = null,
    val ema60: List<Float?>? = null,
    val rsi12: List<Float?>? = null,
    val rsi24: List<Float?>? = null,
    val ma5: List<Float?>? = null,
    val ma10: List<Float?>? = null,
    val ma20: List<Float?>? = null,
    val ma60: List<Float?>? = null,
    // 布林带
    val bollUpper: List<Float?>? = null,
    val bollMid: List<Float?>? = null,
    val bollLower: List<Float?>? = null
)

/**
 * 简化版K线数据（用于图表）
 */
@Serializable
data class CandleData(
    val date: String,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float,
    val turnover: Float? = null,
    val changePercent: Float? = null
)

/**
 * 成交量分布
 */
@Serializable
data class VolumeProfile(
    val priceLevels: List<Float>,
    val volumes: List<Float>,
    val pocPrice: Float,        // 控制点（最大成交量价格）
    val valueAreaHigh: Float,   // 价值区高点
    val valueAreaLow: Float     // 价值区低点
)

/**
 * K线形态识别结果
 */
@Serializable
data class CandlePattern(
    val name: String,
    val type: PatternType,
    val confidence: Float,
    val description: String,
    val date: LocalDate
)

/**
 * K线形态类型
 */
@Serializable
enum class PatternType {
    BULLISH,        // 看涨
    BEARISH,        // 看跌
    NEUTRAL,        // 中性
    REVERSAL,       // 反转
    CONTINUATION    // 持续
}

// ==================== 已弃用的兼容类型别名 ====================
// 以下类型别名用于向后兼容，将在后续版本中移除

/**
 * 股票K线完整信息（已弃用，使用List<Candle>替代）
 * 保留此类用于API兼容性
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class StockKLineInfo(
    val id: Uuid = Uuid.random(),
    val stockInfo: StockInfo,
    val kLines: List<LegacyKLineData>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val period: CandlePeriod,
    val highestPrice: Float,
    val lowestPrice: Float,
    val maxVolume: Float,
    val totalTurnover: Float
) {
    /**
     * 获取最新K线
     */
    val latestKLine: LegacyKLineData?
        get() = kLines.lastOrNull()

    /**
     * 获取K线数量
     */
    val count: Int
        get() = kLines.size

    /**
     * 计算价格区间
     */
    val priceRange: Float
        get() = highestPrice - lowestPrice

    /**
     * 计算整体涨跌幅
     */
    val totalChangePercent: Float
        get() = if (kLines.size >= 2) {
            val first = kLines.first().close
            val last = kLines.last().close
            ((last - first) / first) * 100
        } else 0f
}

/**
 * 单根K线数据（已弃用，使用Candle替代）
 * 保留此类用于API兼容性
 */
@Serializable
data class LegacyKLineData(
    val date: LocalDate,
    val timestamp: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float,
    val turnover: Float,
    val changePercent: Float,
    val amplitude: Float,
    val ema5: Float? = null,
    val ema10: Float? = null,
    val ema20: Float? = null,
    val ema60: Float? = null,
    val rsi6: Float? = null,
    val rsi12: Float? = null,
    val rsi24: Float? = null,
    val macdDif: Float? = null,
    val macdDea: Float? = null,
    val macdBar: Float? = null,
    val ma5: Float? = null,
    val ma10: Float? = null,
    val ma20: Float? = null,
    val ma60: Float? = null
) {
    /**
     * 是否为阳线
     */
    val isBullish: Boolean
        get() = close >= open

    /**
     * 实体大小
     */
    val bodySize: Float
        get() = kotlin.math.abs(close - open)

    /**
     * 上影线长度
     */
    val upperShadow: Float
        get() = high - kotlin.math.max(open, close)

    /**
     * 下影线长度
     */
    val lowerShadow: Float
        get() = kotlin.math.min(open, close) - low

    /**
     * 总振幅
     */
    val totalRange: Float
        get() = high - low
}

// 类型别名保持向后兼容
@Deprecated("使用LegacyKLineData替代", ReplaceWith("LegacyKLineData"))
typealias KLineData = LegacyKLineData

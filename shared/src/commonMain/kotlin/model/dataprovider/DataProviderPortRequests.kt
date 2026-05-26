package model.dataprovider

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import model.candle.CandlePeriod
import model.PriceBasis

/**
 * 历史日线加载请求。
 *
 * 语义说明：
 * - 该请求只面向“日线历史窗口 H”
 * - 支持按最近 N 条读取，也支持按日期区间读取
 * - 是否真正使用 `limit` 还是日期区间，由具体加载器按数据源能力决定
 */
@Serializable
data class HistoricalDailyCandleRequest(
    val tsCode: String,
    val limit: Int = 500,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null
)

/**
 * 历史日线批处理抓取请求。
 *
 * 这个请求专门服务于盘后“按交易日抓全市场”的同步链路。
 * 与单只股票历史刷新不同，这里表达的是：
 * - 以一个交易日为单位
 * - 一次性从 Tushare 获取全市场该日的日线事实
 * - 后续再统一落库并推进交易日历状态
 */
@Serializable
data class HistoricalDailyBatchRequest(
    val tradeDate: LocalDate
)

/**
 * 历史分钟线加载请求。
 *
 * 语义说明：
 * - `period` 只能是分钟级周期
 * - `startTime` / `endTime` 采用远端接口当前要求的时间字符串格式
 * - 第一阶段保留字符串，是为了优先复用现有 Tushare 接口能力
 */
@Serializable
data class HistoricalMinuteCandleRequest(
    val tsCode: String,
    val period: CandlePeriod,
    val limit: Int = 300,
    val startTime: String? = null,
    val endTime: String? = null
)

/**
 * 历史周/月线加载请求。
 *
 * 语义说明：
 * - `period` 只能是 `WEEK` 或 `MONTH`
 * - 日期字符串沿用现有远端接口格式能力，便于直接复用已有实现
 * - 该请求只服务于聚合周期的历史窗口 H，不承担盘中实时语义
 */
@Serializable
data class HistoricalWeeklyMonthlyCandleRequest(
    val tsCode: String,
    val period: CandlePeriod,
    val limit: Int = 300,
    val startDate: String? = null,
    val endDate: String? = null
)

/**
 * 实时日线加载请求。
 *
 * 这里显式支持批量代码，是因为 `rt_k` 原生就支持多代码或通配符查询。
 * 对于单个 Provider 场景，也可以只传一只股票代码。
 */
@Serializable
data class RealtimeDailyCandleRequest(
    val tsCodes: List<String>
)

/**
 * 实时分钟线加载请求。
 *
 * 语义上表示：
 * - 获取指定股票在当前交易日内的实时分钟窗口全量
 * - 返回结果应当已经是时间升序，可直接作为 R 窗口覆盖
 */
@Serializable
data class RealtimeMinuteCandleRequest(
    val tsCode: String,
    val period: CandlePeriod
)

/**
 * 历史情绪窗口加载请求。
 */
@Serializable
data class HistoricalSentimentRequest(
    val endDateInclusive: LocalDate,
    val limit: Int = 252
)

/**
 * 历史因子窗口加载请求。
 *
 * 当前阶段只收敛“按交易日读取”与“读取某日之前最新快照”两类需求，
 * 因为这已经覆盖了盘中初始化和日终同步的主路径。
 */
@Serializable
data class HistoricalFactorRequest(
    val tradeDate: LocalDate,
    val tsCodes: List<String> = emptyList()
)

/**
 * 历史因子滚动状态加载请求。
 *
 * 这个请求专门服务于盘中因子无损递推：
 * - `tradeDate` 必须是 T 日盘中计算所依赖的 T-1 历史状态日期
 * - 返回结果应来自 `daily_factor_rolling_state`
 * - 不能用日频因子快照近似替代完整滚动状态
 */
@Serializable
data class HistoricalFactorStateRequest(
    val tradeDate: LocalDate,
    val tsCodes: List<String> = emptyList()
)

/**
 * 盘中因子增量所需的历史日线窗口请求。
 *
 * 这个请求的目标不是通用历史查询，而是精确服务于盘中增量公式：
 * - 只读取交易日之前的历史窗口
 * - 返回标准化后的 `PreparedBar`
 * - 窗口长度至少满足 `momentum20 / volRatio520 / amomCombined` 所需
 */
@Serializable
data class HistoricalPreparedBarRequest(
    val tsCodes: List<String>,
    val endDateExclusive: LocalDate,
    val limit: Int = 20,
    val signalBasis: PriceBasis = PriceBasis.HFQ,
    val executionBasis: PriceBasis = PriceBasis.RAW
)

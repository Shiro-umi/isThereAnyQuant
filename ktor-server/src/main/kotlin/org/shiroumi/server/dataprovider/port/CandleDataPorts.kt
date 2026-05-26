package org.shiroumi.server.dataprovider.port

import model.Candle
import model.dataprovider.HistoricalDailyBatchRequest
import model.dataprovider.HistoricalDailyCandleRequest
import model.dataprovider.HistoricalMinuteCandleRequest
import model.dataprovider.HistoricalWeeklyMonthlyCandleRequest
import model.dataprovider.RealtimeDailyCandleRequest
import model.dataprovider.RealtimeMinuteCandleRequest

/**
 * 远端历史日线抓取端口。
 *
 * 这个端口只表达“从真实数据源抓取历史日线原始事实”：
 * - 数据源必须是 Tushare 日线接口族
 * - 返回值必须已经被标准化成统一 `Candle`
 * - 不承担持久化职责
 * - 不承担 H 轨道读取职责
 */
interface RemoteHistoricalDailyCandleFetcher {
    suspend fun fetch(request: HistoricalDailyCandleRequest): List<Candle>
}

/**
 * 远端历史日线批处理抓取端口。
 *
 * 这个端口专门服务于盘后全市场日线同步：
 * - 按交易日抓取全市场当日事实
 * - 数据源必须严格沿用旧链路的 `daily + adj_factor + daily_basic`
 * - 返回结果是当日所有股票的标准化 `Candle`
 *
 * 它和单只股票的 `RemoteHistoricalDailyCandleFetcher` 不能混用，
 * 因为两者的任务粒度、吞吐模型和后续落库语义都不同。
 */
interface RemoteHistoricalDailyBatchFetcher {
    suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle>
}

/**
 * 历史日线读取端口。
 *
 * 这个端口只面向已经落库的日线历史 H 轨道。
 * 其返回结果必须来自数据库，而不是直接来自远端接口。
 */
interface HistoricalDailyCandleLoader {
    suspend fun load(request: HistoricalDailyCandleRequest): List<Candle>
}

/**
 * 远端历史分钟线抓取端口。
 *
 * 对应旧链路的 `stk_mins`：
 * - 数据直接来自 Tushare
 * - 不落库
 * - 返回结果会直接成为分钟级 H 轨道
 */
interface RemoteHistoricalMinuteCandleFetcher {
    suspend fun load(request: HistoricalMinuteCandleRequest): List<Candle>
}

/**
 * 远端历史周线抓取端口。
 *
 * 对应旧链路的 `weekly` 接口：
 * - 数据直接来自 Tushare
 * - 不落库
 * - 返回结果直接成为 `WEEK` 周期的 H 轨道
 */
interface RemoteHistoricalWeeklyCandleFetcher {
    suspend fun load(request: HistoricalWeeklyMonthlyCandleRequest): List<Candle>
}

/**
 * 远端历史月线抓取端口。
 *
 * 对应旧链路的 `monthly` 接口：
 * - 数据直接来自 Tushare
 * - 不落库
 * - 返回结果直接成为 `MONTH` 周期的 H 轨道
 */
interface RemoteHistoricalMonthlyCandleFetcher {
    suspend fun load(request: HistoricalWeeklyMonthlyCandleRequest): List<Candle>
}

/**
 * 实时日线加载端口。
 *
 * 它提供的是“当前交易日实时日线窗口 R”的读取能力。
 * 第一阶段接口保留批量能力，以适配 `rt_k` 的原生特性。
 */
interface RealtimeDailyCandleLoader {
    suspend fun load(request: RealtimeDailyCandleRequest): List<Candle>
}

/**
 * 实时分钟线加载端口。
 *
 * 它返回的是指定股票在当前交易日内的实时分钟窗口全量，
 * 结果可直接覆盖写入 Provider 的 R 轨道。
 */
interface RealtimeMinuteCandleLoader {
    suspend fun load(request: RealtimeMinuteCandleRequest): List<Candle>
}

/**
 * 历史日线落库端口。
 *
 * 这里刻意不做“泛化历史 K 线落库”，而是只表达日线落库：
 * 1. 现有 `stock_daily_data` 表明确承载的是日线
 * 2. 分钟/周/月历史当前不进入持久化模型
 * 3. 如果把分钟、周线、月线硬塞进同一个持久化口，会把领域语义做坏
 */
interface HistoricalDailyCandlePersister {
    suspend fun persist(candles: List<Candle>)
}

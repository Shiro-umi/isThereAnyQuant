package org.shiroumi.server.dataprovider.service

import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.dataprovider.HistoricalDailyCandleRequest
import model.dataprovider.HistoricalMinuteCandleRequest
import model.dataprovider.HistoricalWeeklyMonthlyCandleRequest
import org.shiroumi.server.dataprovider.port.HistoricalDailyCandleLoader
import org.shiroumi.server.dataprovider.port.HistoricalDailyCandlePersister
import org.shiroumi.server.dataprovider.port.RemoteHistoricalDailyCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalMinuteCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalMonthlyCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalWeeklyCandleFetcher

/**
 * K 线历史同步服务。
 *
 * 这个服务是新架构中“历史窗口 H 如何建立”的统一入口。
 * 它把不同周期的历史语义显式收敛成三条稳定链路：
 *
 * 1. `DAY`
 *    `Tushare(daily + adj_factor + daily_basic) -> normalize -> persist -> load H`
 * 2. `MIN_*`
 *    `Tushare(stk_mins) -> normalize -> H`
 * 3. `WEEK / MONTH`
 *    `Tushare(weekly/monthly) -> normalize -> H`
 *
 * 这样 Provider 自己只关心“拿到最终的历史轨道”，
 * 而不会再在内部混杂 remote、persist、reload 的细节。
 */
interface CandleHistoricalSyncService {
    /**
     * 加载指定 key 的历史轨道。
     *
     * `allowPersistence` 不是一个“性能优化开关”，而是明确的业务边界：
     * - `true` 只允许在 `DAY` 的正式历史同步场景下使用
     * - `false` 表示当前只允许读取既有历史结果，绝不能触发落库
     *
     * 这样可以避免盘中订阅预热时，把尚未收盘的当日日线写入 `stock_daily_data`。
     */
    suspend fun loadHistorical(
        key: CandleKey,
        dailyHistoryLimit: Int = 500,
        minuteHistoryLimit: Int = 500,
        weeklyMonthlyHistoryLimit: Int = 500,
        allowPersistence: Boolean = true
    ): List<Candle>
}

/**
 * 默认的 K 线历史同步服务实现。
 */
class DefaultCandleHistoricalSyncService(
    private val remoteHistoricalDailyFetcher: RemoteHistoricalDailyCandleFetcher,
    private val historicalDailyPersister: HistoricalDailyCandlePersister,
    private val historicalDailyLoader: HistoricalDailyCandleLoader,
    private val remoteHistoricalMinuteFetcher: RemoteHistoricalMinuteCandleFetcher,
    private val remoteHistoricalWeeklyFetcher: RemoteHistoricalWeeklyCandleFetcher,
    private val remoteHistoricalMonthlyFetcher: RemoteHistoricalMonthlyCandleFetcher,
) : CandleHistoricalSyncService {

    /**
     * 根据周期类型加载最终历史轨道。
     *
     * 这里的“最终”有明确含义：
     * - `DAY` 返回的是已落库后重载出的 H
     * - `MIN_* / WEEK / MONTH` 返回的是按旧链路语义直接来自远端的 H
     */
    override suspend fun loadHistorical(
        key: CandleKey,
        dailyHistoryLimit: Int,
        minuteHistoryLimit: Int,
        weeklyMonthlyHistoryLimit: Int,
        allowPersistence: Boolean
    ): List<Candle> = when (key.period) {
        CandlePeriod.DAY -> {
            if (allowPersistence) {
                syncDaily(
                    tsCode = key.tsCode,
                    limit = dailyHistoryLimit
                )
            } else {
                loadPersistedDaily(
                    tsCode = key.tsCode,
                    limit = dailyHistoryLimit
                )
            }
        }

        CandlePeriod.MIN_5,
        CandlePeriod.MIN_15,
        CandlePeriod.MIN_30,
        CandlePeriod.MIN_60 -> remoteHistoricalMinuteFetcher.load(
            HistoricalMinuteCandleRequest(
                tsCode = key.tsCode,
                period = key.period,
                limit = minuteHistoryLimit
            )
        )

        CandlePeriod.WEEK -> remoteHistoricalWeeklyFetcher.load(
            HistoricalWeeklyMonthlyCandleRequest(
                tsCode = key.tsCode,
                period = key.period,
                limit = weeklyMonthlyHistoryLimit
            )
        )

        CandlePeriod.MONTH -> remoteHistoricalMonthlyFetcher.load(
            HistoricalWeeklyMonthlyCandleRequest(
                tsCode = key.tsCode,
                period = key.period,
                limit = weeklyMonthlyHistoryLimit
            )
        )
    }

    /**
     * 同步单只股票的日线历史。
     *
     * 这是新架构里唯一必须严格满足
     * `remote -> normalize -> persist -> memory`
     * 的 K 线历史链路。
     */
    private suspend fun syncDaily(
        tsCode: String,
        limit: Int
    ): List<Candle> {
        val remoteCandles = remoteHistoricalDailyFetcher.fetch(
            HistoricalDailyCandleRequest(
                tsCode = tsCode,
                limit = limit
            )
        )
        historicalDailyPersister.persist(remoteCandles)
        return historicalDailyLoader.load(
            HistoricalDailyCandleRequest(
                tsCode = tsCode,
                limit = limit
            )
        )
    }

    /**
     * 只读方式加载已存在的日线历史。
     *
     * 该入口专门给盘中 warmup 使用：
     * - 只读数据库已有 H
     * - 不触发远端抓取
     * - 不触发持久化
     */
    private suspend fun loadPersistedDaily(
        tsCode: String,
        limit: Int
    ): List<Candle> {
        return historicalDailyLoader.load(
            HistoricalDailyCandleRequest(
                tsCode = tsCode,
                limit = limit
            )
        )
    }
}

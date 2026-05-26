package org.shiroumi.server.dataprovider.adapter

import kotlinx.datetime.LocalDate
import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.HistoricalDailyBatchRequest
import model.dataprovider.HistoricalDailyCandleRequest
import model.dataprovider.HistoricalMinuteCandleRequest
import model.dataprovider.HistoricalWeeklyMonthlyCandleRequest
import model.dataprovider.RealtimeDailyCandleRequest
import model.dataprovider.RealtimeMinuteCandleRequest
import org.shiroumi.database.stock.LimitListDRecord
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.network.apis.Column
import org.shiroumi.network.apis.getAdjFactor
import org.shiroumi.network.apis.getDailyCandles
import org.shiroumi.network.apis.getDailyInfo
import org.shiroumi.network.apis.getLimitListD
import org.shiroumi.network.apis.getRtDaily
import org.shiroumi.network.apis.getRtMinDaily
import org.shiroumi.network.apis.getStkMins
import org.shiroumi.network.apis.getMonthlyCandles
import org.shiroumi.network.apis.getWeeklyCandles
import org.shiroumi.network.apis.tushare
import org.shiroumi.server.dataprovider.port.HistoricalDailyCandlePersister
import org.shiroumi.server.dataprovider.port.HistoricalDailyCandleLoader
import org.shiroumi.server.dataprovider.port.RemoteHistoricalDailyBatchFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalDailyCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalMinuteCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalMonthlyCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteHistoricalWeeklyCandleFetcher
import org.shiroumi.server.dataprovider.port.RemoteLimitListDFetcher
import org.shiroumi.server.dataprovider.port.RealtimeDailyCandleLoader
import org.shiroumi.server.dataprovider.port.RealtimeMinuteCandleLoader
import org.shiroumi.server.runtime.market.resolveEffectiveTradeDate
import utils.logger
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 基于 `StockReader` 的历史日线加载适配器。
 *
 * 复用策略：
 * - 日期区间查询时，直接复用现有数据库读取能力
 * - 仅给 limit 时，复用现有最近 N 条查询能力
 *
 * 这样 `P2` 就不需要重复实现数据库层的日线读取逻辑。
 */
class StockReaderHistoricalDailyCandleLoader : HistoricalDailyCandleLoader {
    override suspend fun load(request: HistoricalDailyCandleRequest): List<Candle> {
        val startDate = request.startDate
        val endDate = request.endDate
        return when {
            startDate != null && endDate != null ->
                StockReader.getStockHistory(request.tsCode, startDate, endDate).takeLast(request.limit)

            else ->
                StockReader.getStockHistory(request.tsCode, request.limit)
        }
    }
}

/**
 * 基于 Tushare `stk_mins` 的历史分钟线加载适配器。
 *
 * 这里不再复用旧 `MinuteCandleService`，而是直接复用 network 模块能力：
 * - 避免新架构继续依赖旧 service 作为实现承载层
 * - 保持“端口 -> 适配器 -> 远端接口”这条依赖方向清晰
 */
class TushareHistoricalDailyCandleFetcher : RemoteHistoricalDailyCandleFetcher {
    private val logger by logger("TushareHistoricalDailyCandleFetcher")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun fetch(request: HistoricalDailyCandleRequest): List<Candle> {
        return try {
            val startDate = request.startDate
            val endDate = request.endDate
            /**
             * 这里严格沿用旧盘后更新链路的数据来源：
             * - daily
             * - adj_factor
             * - daily_basic
             *
             * 三个接口共同构成“最终可落库的日线事实”。
             */
            val dailyItems = tushare.getDailyCandles(tsCode = request.tsCode).check()?.items?.asReversed().orEmpty()
            val adjItems = tushare.getAdjFactor(tsCode = request.tsCode).check()?.items?.asReversed().orEmpty()
            val infoItems = tushare.getDailyInfo(tsCode = request.tsCode).check()?.items?.asReversed().orEmpty()

            val dailyByDate = dailyItems.associateBy { "${it[1]}" }
            val adjByDate = adjItems.associateBy { "${it[1]}" }
            val infoByDate = infoItems.associateBy { "${it[1]}" }
            val commonDates = dailyByDate.keys
                .intersect(adjByDate.keys)
                .intersect(infoByDate.keys)
                .sorted()

            val latestAdj = commonDates.lastOrNull()?.let { date ->
                "${adjByDate.getValue(date)[2]}".toFloatOrNull()
            } ?: 1f

            commonDates.mapNotNull { tradeDate ->
                val daily = dailyByDate[tradeDate] ?: return@mapNotNull null
                val adj = adjByDate[tradeDate] ?: return@mapNotNull null
                val info = infoByDate[tradeDate] ?: return@mapNotNull null

                val rawDate = tradeDate.parseTushareTradeDate()
                if (startDate != null && rawDate < startDate) return@mapNotNull null
                if (endDate != null && rawDate > endDate) return@mapNotNull null

                val open = "${daily[2]}".toFloatOrNull() ?: 0f
                val high = "${daily[3]}".toFloatOrNull() ?: 0f
                val low = "${daily[4]}".toFloatOrNull() ?: 0f
                val close = "${daily[5]}".toFloatOrNull() ?: 0f
                val volume = "${daily[9]}".toFloatOrNull() ?: 0f
                val adjVal = "${adj[2]}".toFloatOrNull() ?: 1f

                Candle(
                    id = Uuid.random(),
                    tsCode = request.tsCode,
                    date = rawDate,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    adj = adjVal,
                    openQfq = (adjVal / latestAdj) * open,
                    closeQfq = (adjVal / latestAdj) * close,
                    highQfq = (adjVal / latestAdj) * high,
                    lowQfq = (adjVal / latestAdj) * low,
                    volume = volume,
                    volumeQfq = (latestAdj / adjVal) * volume,
                    turnoverReal = "${info[4] ?: -1}".toFloatOrNull() ?: 0f,
                    pe = "${info[6] ?: -1}".toFloatOrNull() ?: 0f,
                    peTtm = "${info[7] ?: -1}".toFloatOrNull() ?: 0f,
                    pb = "${info[8] ?: -1}".toFloatOrNull() ?: 0f,
                    ps = "${info[9] ?: -1}".toFloatOrNull() ?: 0f,
                    psTtm = "${info[10] ?: -1}".toFloatOrNull() ?: 0f,
                    mvTotal = "${info[16] ?: -1}".toFloatOrNull() ?: 0f,
                    mvCirc = "${info[17] ?: -1}".toFloatOrNull() ?: 0f
                )
            }.takeLast(request.limit)
        } catch (e: Exception) {
            logger.error("抓取历史日线失败 ${request.tsCode}: ${e.message}")
            throw e
        }
    }
}

/**
 * 基于 Tushare 日线接口族的“按交易日抓全市场”适配器。
 *
 * 这是盘后批处理日线同步的专用数据源：
 * - 输入是一日交易日
 * - 输出是该交易日全市场的标准化 `Candle`
 * - 不负责持久化，只负责把三个远端接口结果对齐成单日事实
 *
 * 它必须和单只股票历史刷新适配器分开，原因是：
 * - 任务粒度不同
 * - 吞吐优化目标不同
 * - 后续 calendar 状态推进语义不同
 */
class TushareHistoricalDailyBatchFetcher : RemoteHistoricalDailyBatchFetcher {
    private val logger by logger("TushareHistoricalDailyBatchFetcher")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun fetch(request: HistoricalDailyBatchRequest): List<Candle> {
        val tradeDate = request.tradeDate.toString().replace("-", "")
        return try {
            val rawCandleList = tushare.getDailyCandles(date = tradeDate).check()?.items?.asReversed().orEmpty()
            val rawAdjList = tushare.getAdjFactor(date = tradeDate).check()?.items?.asReversed().orEmpty()
            val rawDailyInfoList = tushare.getDailyInfo(date = tradeDate).check()?.items?.asReversed().orEmpty()

            val candleCodes = rawCandleList.map { it[0].orEmpty() }.toSet()
            val adjCodes = rawAdjList.map { it[0].orEmpty() }.toSet()
            val infoCodes = rawDailyInfoList.map { it[0].orEmpty() }.toSet()
            val commonCodes = candleCodes.intersect(adjCodes).intersect(infoCodes)

            val candleList = rawCandleList.filter { it[0] in commonCodes }.sortedBy { it[0] }
            val adjList = rawAdjList.filter { it[0] in commonCodes }.sortedBy { it[0] }
            val infoList = rawDailyInfoList.filter { it[0] in commonCodes }.sortedBy { it[0] }

            candleList.indices.map { index ->
                val candle = candleList[index]
                val adjFactor = adjList[index]
                val info = infoList[index]
                Candle(
                    tsCode = candle.requiredString(0, "ts_code", request.tradeDate),
                    date = candle.requiredString(1, "trade_date", request.tradeDate).parseTushareTradeDate(),
                    turnoverReal = info.optionalFloatOrDefault(4, defaultValue = -1f),
                    pe = info.optionalFloatOrDefault(6, defaultValue = -1f),
                    peTtm = info.optionalFloatOrDefault(7, defaultValue = -1f),
                    pb = info.optionalFloatOrDefault(8, defaultValue = -1f),
                    ps = info.optionalFloatOrDefault(9, defaultValue = -1f),
                    psTtm = info.optionalFloatOrDefault(10, defaultValue = -1f),
                    mvTotal = info.optionalFloatOrDefault(16, defaultValue = -1f),
                    mvCirc = info.optionalFloatOrDefault(17, defaultValue = -1f),
                    open = candle.requiredFloat(2, "open", request.tradeDate),
                    high = candle.requiredFloat(3, "high", request.tradeDate),
                    low = candle.requiredFloat(4, "low", request.tradeDate),
                    close = candle.requiredFloat(5, "close", request.tradeDate),
                    volume = candle.requiredFloat(9, "vol", request.tradeDate),
                    adj = adjFactor.requiredFloat(2, "adj_factor", request.tradeDate),
                    openQfq = 0f,
                    closeQfq = 0f,
                    highQfq = 0f,
                    lowQfq = 0f,
                    volumeQfq = 0f
                )
            }
        } catch (e: Exception) {
            logger.error("按交易日抓取历史日线失败 ${request.tradeDate}: ${e.message}")
            throw e
        }
    }
}

/**
 * 基于 Tushare `limit_list_d` 的每日涨跌停、炸板事实抓取器。
 *
 * 单日按 U/D/Z 拆分拉取，避免接口单次 2500 条上限截断全市场极端行情日。
 */
class TushareLimitListDFetcher : RemoteLimitListDFetcher {
    private val logger by logger("TushareLimitListDFetcher")

    override suspend fun fetch(tradeDate: LocalDate): List<LimitListDRecord> {
        val tushareDate = tradeDate.toString().replace("-", "")
        return try {
            LIMIT_LIST_D_TYPES
                .flatMap { limitType ->
                    tushare.getLimitListD(
                        tradeDate = tushareDate,
                        limitType = limitType
                    ).check()
                        ?.toColumns()
                        .orEmpty()
                        .mapNotNull { column ->
                            column.toLimitListDRecord(
                                defaultTradeDate = tradeDate,
                                defaultLimitType = limitType
                            )
                        }
                }
                .distinctBy { "${it.tradeDate}|${it.tsCode}|${it.limitType}" }
                .sortedWith(compareBy<LimitListDRecord> { it.limitType }.thenBy { it.tsCode })
        } catch (error: Exception) {
            logger.error("按交易日抓取涨跌停炸板数据失败 $tradeDate: ${error.message}")
            throw error
        }
    }

    private fun Column.toLimitListDRecord(
        defaultTradeDate: LocalDate,
        defaultLimitType: String
    ): LimitListDRecord? {
        val tsCode = provides("ts_code").ifBlank { return null }
        val tradeDate = provides("trade_date")
            .takeIf { it.isNotBlank() }
            ?.parseTushareTradeDate()
            ?: defaultTradeDate
        val limitType = provides("limit").ifBlank { provides("limit_type") }.ifBlank { defaultLimitType }
        return LimitListDRecord(
            tradeDate = tradeDate,
            tsCode = tsCode,
            industry = provides("industry").ifBlank { null },
            name = provides("name").ifBlank { null },
            close = provides("close").toDoubleOrNull(),
            pctChg = provides("pct_chg").toDoubleOrNull(),
            amount = provides("amount").toDoubleOrNull(),
            limitAmount = provides("limit_amount").toDoubleOrNull(),
            floatMv = provides("float_mv").toDoubleOrNull(),
            totalMv = provides("total_mv").toDoubleOrNull(),
            turnoverRatio = provides("turnover_ratio").toDoubleOrNull(),
            fdAmount = provides("fd_amount").toDoubleOrNull(),
            firstTime = provides("first_time").ifBlank { null },
            lastTime = provides("last_time").ifBlank { null },
            openTimes = provides("open_times").toIntOrNull(),
            upStat = provides("up_stat").ifBlank { null },
            limitTimes = provides("limit_times").toIntOrNull(),
            limitType = limitType,
        )
    }
}

private val LIMIT_LIST_D_TYPES = listOf("U", "D", "Z")

/**
 * 批处理链路必须对关键字段做严格校验。
 *
 * 原因不是“代码风格更严格”，而是盘后同步一旦把错误数据落库并推进 calendar，
 * 这个交易日就不会再自动重试。这里必须保持 fail-fast。
 */
private fun List<String?>.requiredString(
    index: Int,
    fieldName: String,
    tradeDate: LocalDate
): String {
    val value = getOrNull(index)?.takeIf { it.isNotBlank() }
    require(!value.isNullOrBlank()) {
        "交易日 $tradeDate 缺少字段 $fieldName"
    }
    return value
}

private fun List<String?>.requiredFloat(
    index: Int,
    fieldName: String,
    tradeDate: LocalDate
): Float {
    val raw = requiredString(index, fieldName, tradeDate)
    return raw.toFloatOrNull()
        ?: error("交易日 $tradeDate 字段 $fieldName 不是合法数字: $raw")
}

private fun List<String?>.optionalFloatOrDefault(index: Int, defaultValue: Float): Float {
    val raw = getOrNull(index)?.takeIf { it.isNotBlank() } ?: return defaultValue
    return raw.toFloatOrNull() ?: defaultValue
}

/**
 * 日线持久化适配器。
 *
 * 这里通过 `database` 模块公开的稳定 Repository 边界完成落库，
 * 而不是在 `ktor-server` 越过边界直接操纵表事务。
 */
class RepositoryHistoricalDailyCandlePersister : HistoricalDailyCandlePersister {
    override suspend fun persist(candles: List<Candle>) {
        StockDailyCandleRepository.replaceCandles(candles)
    }
}

/**
 * 基于 Tushare `stk_mins` 的历史分钟线抓取适配器。
 */
class TushareHistoricalMinuteCandleFetcher : RemoteHistoricalMinuteCandleFetcher {
    private val logger by logger("TushareHistoricalMinuteCandleFetcher")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun load(request: HistoricalMinuteCandleRequest): List<Candle> {
        require(request.period.isMinutePeriod()) {
            "历史分钟线加载端口只支持分钟周期，当前收到: ${request.period}"
        }

        return try {
            /**
             * 这里必须保留旧链路里的“安全回看窗口”语义。
             *
             * 背景：
             * - `stk_mins` 如果不给开始时间，返回窗口大小并不稳定
             * - Provider 的典型诉求是“给我最近 N 根分钟线”
             * - 如果这里只把 null 透传给远端，最终返回的数据量可能不足以初始化指标窗口
             *
             * 因此，当调用方只给 `limit`、没有显式给时间区间时，
             * 我们要先按周期和目标数量反推出一个足够保守的开始时间，
             * 再去请求远端接口。
             */
            val effectiveStartTime = request.startTime ?: calculateSafeHistoricalMinuteStartTime(
                limit = request.limit,
                period = request.period,
                endTime = request.endTime
            )

            val response = tushare.getStkMins(
                tsCode = request.tsCode,
                freq = request.period.toMinuteFreq(),
                startDate = effectiveStartTime,
                endDate = request.endTime
            )
            val data = response.check() ?: return emptyList()

            data.toColumns(sortKey = "trade_time")
                .takeLast(request.limit)
                .map { col ->
                    val tradeTime = col provides "trade_time"
                    val open = (col provides "open").toFloatOrNull() ?: 0f
                    val close = (col provides "close").toFloatOrNull() ?: 0f
                    val high = (col provides "high").toFloatOrNull() ?: 0f
                    val low = (col provides "low").toFloatOrNull() ?: 0f
                    val volume = (col provides "vol").toFloatOrNull() ?: 0f
                    val amount = (col provides "amount").toFloatOrNull() ?: 0f
                    val date = tradeTime.parseTushareTradeDate()

                    Candle(
                        id = Uuid.random(),
                        tsCode = request.tsCode,
                        date = date,
                        tradeTime = tradeTime,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        adj = 1f,
                        openQfq = open,
                        closeQfq = close,
                        highQfq = high,
                        lowQfq = low,
                        volume = volume,
                        volumeQfq = volume,
                        turnoverReal = amount * 1000f,
                        pe = 0f,
                        peTtm = 0f,
                        pb = 0f,
                        ps = 0f,
                        psTtm = 0f,
                        mvTotal = 0f,
                        mvCirc = 0f
                    )
                }
        } catch (e: Exception) {
            logger.error("加载历史分钟线失败 ${request.tsCode} ${request.period}: ${e.message}")
            emptyList()
        }
    }
}

/**
 * 基于现有 `WeeklyMonthlyCandleService` 的周/月历史加载适配器。
 *
 * 这里允许复用旧 service 的原因是：
 * - 它本身是无状态的远端历史数据读取能力
 * - 不持有业务真相状态
 * - 不会把新 Provider 重新耦合回旧 websocket / polling 链路
 */
class TushareHistoricalWeeklyCandleFetcher : RemoteHistoricalWeeklyCandleFetcher {
    override suspend fun load(request: HistoricalWeeklyMonthlyCandleRequest): List<Candle> {
        require(request.period == CandlePeriod.WEEK) {
            "周线历史抓取端口只支持 WEEK，当前收到: ${request.period}"
        }

        val effectiveEndDate = request.endDate ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val effectiveStartDate = request.startDate
            ?: calculateDefaultPeriodicStartDate(
                limit = request.limit,
                period = request.period,
                endDate = effectiveEndDate
            )

        val response = tushare.getWeeklyCandles(
            tsCode = request.tsCode,
            startDate = effectiveStartDate.replace("-", ""),
            endDate = effectiveEndDate.replace("-", "")
        )
        val data = response.check() ?: return emptyList()
        return data.toColumns(sortKey = "trade_date")
            .takeLast(request.limit)
            .map { it.toPeriodicCandle(request.tsCode) }
    }
}

/**
 * 基于 Tushare `monthly` 的历史月线抓取适配器。
 */
class TushareHistoricalMonthlyCandleFetcher : RemoteHistoricalMonthlyCandleFetcher {
    override suspend fun load(request: HistoricalWeeklyMonthlyCandleRequest): List<Candle> {
        require(request.period == CandlePeriod.MONTH) {
            "月线历史抓取端口只支持 MONTH，当前收到: ${request.period}"
        }

        val effectiveEndDate = request.endDate ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val effectiveStartDate = request.startDate
            ?: calculateDefaultPeriodicStartDate(
                limit = request.limit,
                period = request.period,
                endDate = effectiveEndDate
            )

        val response = tushare.getMonthlyCandles(
            tsCode = request.tsCode,
            startDate = effectiveStartDate.replace("-", ""),
            endDate = effectiveEndDate.replace("-", "")
        )
        val data = response.check() ?: return emptyList()
        return data.toColumns(sortKey = "trade_date")
            .takeLast(request.limit)
            .map { it.toPeriodicCandle(request.tsCode) }
    }
}

/**
 * 按旧 `WeeklyMonthlyCandleService` 的语义，为“只给 limit”的周/月历史请求补一个安全回看区间。
 *
 * 这里不能把 `startDate` 直接留空交给 Tushare 决定默认窗口，
 * 因为那会让返回 bar 数量依赖远端默认行为，导致 Provider 的 H 窗口长度不稳定。
 */
internal fun calculateDefaultPeriodicStartDate(
    limit: Int,
    period: CandlePeriod,
    endDate: String
): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val parsedEndDate = runCatching {
        java.time.LocalDate.parse(endDate, formatter)
    }.getOrElse {
        java.time.LocalDate.now()
    }

    val estimatedDays = when (period) {
        CandlePeriod.WEEK -> (limit * 7 * 1.5).toLong() + 30
        CandlePeriod.MONTH -> (limit * 30 * 1.2).toLong() + 100
        else -> 365L
    }

    return parsedEndDate.minusDays(estimatedDays).format(formatter)
}

internal interface RawRealtimeDailyFactFetcher {
    suspend fun fetch(tsCodes: List<String>, tradeDate: LocalDate): List<Candle>
}

internal interface TradeDateAdjFactorFetcher {
    suspend fun fetch(tradeDate: LocalDate): Map<String, Float>
}

/**
 * 共享的权威 DAY realtime 事实源。
 *
 * 职责是：
 * 1. 统一从 `rt_k` 读取当日 raw realtime facts
 * 2. 补齐当日新的 `adj_factor`
 * 3. 用按股票粒度的短 TTL 缓存，保证多个消费者在同一时间窗看到的是同一份事实
 */
class AuthoritativeRealtimeDailyCandleLoader internal constructor(
    private val rawFactFetcher: RawRealtimeDailyFactFetcher = TushareRtKRawRealtimeDailyFactFetcher(),
    private val tradeDateAdjFactorFetcher: TradeDateAdjFactorFetcher = TushareTradeDateAdjFactorFetcher(),
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    private val tradeDateProvider: () -> LocalDate = { resolveEffectiveTradeDate(clock) },
    private val realtimeFactTtlMs: Long = 1_000L,
    private val missingAdjRetryMs: Long = 30_000L,
    private val rawFactBatchSize: Int = RT_K_BATCH_SIZE,
) : RealtimeDailyCandleLoader {
    private val logger by logger("AuthoritativeRealtimeDailyCandleLoader")
    private val realtimeFactCache = ConcurrentHashMap<String, CachedRealtimeDailyFact>()
    private val realtimeFactMutex = Mutex()
    private val tradeDateAdjMutex = Mutex()

    @Volatile
    private var tradeDateAdjCache: CachedTradeDateAdjFactors? = null

    override suspend fun load(request: RealtimeDailyCandleRequest): List<Candle> {
        val normalizedCodes = request.tsCodes.distinct().sorted()
        if (normalizedCodes.isEmpty()) return emptyList()

        val tradeDate = currentTradeDate()
        val now = clock.millis()

        ensureRealtimeFacts(
            tsCodes = normalizedCodes,
            tradeDate = tradeDate,
            nowMs = now,
        )
        val freshFacts = normalizedCodes.map { tsCode ->
            realtimeFactCache[tsCode]
                ?.takeIf { cached ->
                    cached.tradeDate == tradeDate &&
                        now - cached.cachedAtMs < realtimeFactTtlMs
                }
                ?: error("REALTIME_FACT_UNAVAILABLE:$tsCode")
        }
        val adjByCode = ensureTradeDateAdjFactors(
            tsCodes = normalizedCodes,
            tradeDate = tradeDate,
            nowMs = now,
        )

        return freshFacts.map { cached ->
            enrichRealtimeFact(cached.candle, adjByCode[cached.candle.tsCode])
        }
    }

    /**
     * 用通配符一次性拉取全市场实时日 K。
     *
     * 与 [load] 的语义差异：
     * - 不预先指定 ts_code 集合，结果集就是 fetcher 实际返回的全部 candles
     * - 返回的每个 candle 同步写入 [realtimeFactCache]，给后续按 code 查询复用
     * - 不会因为某只股票"未返回"而抛 REALTIME_FACT_UNAVAILABLE（语义上就是"市场上没这条数据"）
     */
    suspend fun loadByWildcards(wildcards: List<String>): List<Candle> {
        val normalizedWildcards = wildcards.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedWildcards.isEmpty()) return emptyList()

        val tradeDate = currentTradeDate()
        val now = clock.millis()

        val fetched = runCatching {
            rawFactFetcher.fetch(normalizedWildcards, tradeDate)
        }.getOrElse { error ->
            logger.error("通配符方式加载权威实时日线失败 wildcards=$normalizedWildcards: ${error.message}")
            return emptyList()
        }

        if (fetched.isEmpty()) return emptyList()

        realtimeFactMutex.withLock {
            pruneExpiredRealtimeFacts(tradeDate = tradeDate, nowMs = now)
            fetched.forEach { candle ->
                realtimeFactCache[candle.tsCode] = CachedRealtimeDailyFact(
                    tradeDate = tradeDate,
                    candle = candle,
                    cachedAtMs = now
                )
            }
        }

        val adjByCode = ensureTradeDateAdjFactors(
            tsCodes = fetched.map { it.tsCode },
            tradeDate = tradeDate,
            nowMs = now,
        )

        return fetched.map { candle ->
            enrichRealtimeFact(candle, adjByCode[candle.tsCode])
        }
    }

    private suspend fun ensureRealtimeFacts(
        tsCodes: List<String>,
        tradeDate: LocalDate,
        nowMs: Long,
    ) {
        realtimeFactMutex.withLock {
            pruneExpiredRealtimeFacts(tradeDate = tradeDate, nowMs = nowMs)
            val pendingCodes = tsCodes.filter { tsCode ->
                val cached = realtimeFactCache[tsCode]
                cached == null || cached.tradeDate != tradeDate || nowMs - cached.cachedAtMs >= realtimeFactTtlMs
            }
            if (pendingCodes.isEmpty()) return

            val fetchedFacts = fetchPendingRealtimeFacts(pendingCodes, tradeDate)

            val fetchedByCode = fetchedFacts.associateBy { it.tsCode }
            pendingCodes.forEach { tsCode ->
                fetchedByCode[tsCode]?.let { candle ->
                    realtimeFactCache[tsCode] = CachedRealtimeDailyFact(
                        tradeDate = tradeDate,
                        candle = candle,
                        cachedAtMs = nowMs
                    )
                } ?: realtimeFactCache.compute(tsCode) { _, existing ->
                    existing?.takeIf { it.tradeDate == tradeDate }
                }
            }
        }
    }

    private suspend fun fetchPendingRealtimeFacts(
        pendingCodes: List<String>,
        tradeDate: LocalDate
    ): List<Candle> {
        val effectiveBatchSize = rawFactBatchSize.coerceAtLeast(1)
        return pendingCodes.chunked(effectiveBatchSize).flatMap { batch ->
            runCatching {
                rawFactFetcher.fetch(batch, tradeDate)
            }.getOrElse { error ->
                logger.error("加载权威实时日线失败 batchSize=${batch.size}: ${error.message}")
                emptyList()
            }
        }
    }

    private fun pruneExpiredRealtimeFacts(
        tradeDate: LocalDate,
        nowMs: Long,
    ) {
        realtimeFactCache.entries.removeIf { (_, cached) ->
            cached.tradeDate != tradeDate || nowMs - cached.cachedAtMs >= realtimeFactTtlMs
        }
    }

    internal fun cachedRealtimeFactCount(): Int = realtimeFactCache.size

    private suspend fun ensureTradeDateAdjFactors(
        tsCodes: List<String>,
        tradeDate: LocalDate,
        nowMs: Long,
    ): Map<String, Float> {
        val currentCache = tradeDateAdjCache
        val missingCodes = if (currentCache?.tradeDate == tradeDate) {
            tsCodes.filterNot(currentCache.adjByCode::containsKey)
        } else {
            tsCodes
        }
        val shouldRefresh = currentCache == null ||
            currentCache.tradeDate != tradeDate ||
            (missingCodes.isNotEmpty() && nowMs - currentCache.loadedAtMs >= missingAdjRetryMs)

        if (shouldRefresh) {
            tradeDateAdjMutex.withLock {
                val latestCache = tradeDateAdjCache
                val latestMissingCodes = if (latestCache?.tradeDate == tradeDate) {
                    tsCodes.filterNot(latestCache.adjByCode::containsKey)
                } else {
                    tsCodes
                }
                val refreshNeeded = latestCache == null ||
                    latestCache.tradeDate != tradeDate ||
                    (latestMissingCodes.isNotEmpty() && nowMs - latestCache.loadedAtMs >= missingAdjRetryMs)
                if (refreshNeeded) {
                    val loadedAdjByCode = runCatching {
                        tradeDateAdjFactorFetcher.fetch(tradeDate)
                    }.getOrElse { error ->
                        logger.error("加载当日 adj_factor 失败 $tradeDate: ${error.message}")
                        return@withLock
                    }
                    tradeDateAdjCache = CachedTradeDateAdjFactors(
                        tradeDate = tradeDate,
                        adjByCode = loadedAdjByCode,
                        loadedAtMs = nowMs
                    )
                }
            }
        }

        return tradeDateAdjCache
            ?.takeIf { it.tradeDate == tradeDate }
            ?.adjByCode
            .orEmpty()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun enrichRealtimeFact(
        candle: Candle,
        todayAdj: Float?
    ): Candle {
        val normalizedAdj = todayAdj?.takeIf { it > 0f } ?: 0f
        val hasAdj = normalizedAdj > 0f
        return candle.copy(
            adj = normalizedAdj,
            openQfq = if (hasAdj) candle.open else 0f,
            closeQfq = if (hasAdj) candle.close else 0f,
            highQfq = if (hasAdj) candle.high else 0f,
            lowQfq = if (hasAdj) candle.low else 0f,
            volumeQfq = if (hasAdj) candle.volume else 0f
        )
    }

    private fun currentTradeDate(): LocalDate =
        tradeDateProvider()
}

internal class TushareRtKRawRealtimeDailyFactFetcher : RawRealtimeDailyFactFetcher {
    private val logger by logger("TushareRtKRawRealtimeDailyFactFetcher")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun fetch(tsCodes: List<String>, tradeDate: LocalDate): List<Candle> {
        if (tsCodes.isEmpty()) return emptyList()

        return try {
            val response = tushare.getRtDaily(tsCodes.joinToString(","))
            val data = response.check() ?: return emptyList()
            data.toColumns().mapNotNull { col ->
                val tsCode = col.provides("ts_code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val open = col.provides("open").toFloatOrNull() ?: 0f
                val close = col.provides("close").toFloatOrNull() ?: 0f
                val high = col.provides("high").toFloatOrNull() ?: 0f
                val low = col.provides("low").toFloatOrNull() ?: 0f
                val volume = (col.provides("vol").toFloatOrNull() ?: 0f) / 100f
                val amount = col.provides("amount").toFloatOrNull() ?: 0f

                Candle(
                    id = Uuid.random(),
                    tsCode = tsCode,
                    date = tradeDate,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    adj = 0f,
                    openQfq = 0f,
                    closeQfq = 0f,
                    highQfq = 0f,
                    lowQfq = 0f,
                    volume = volume,
                    volumeQfq = 0f,
                    turnoverReal = amount * 1000f,
                    pe = 0f,
                    peTtm = 0f,
                    pb = 0f,
                    ps = 0f,
                    psTtm = 0f,
                    mvTotal = 0f,
                    mvCirc = 0f
                )
            }
        } catch (e: Exception) {
            logger.error("加载 raw 实时日线失败 $tsCodes: ${e.message}")
            emptyList()
        }
    }
}

internal class TushareTradeDateAdjFactorFetcher : TradeDateAdjFactorFetcher {
    private val logger by logger("TushareTradeDateAdjFactorFetcher")

    override suspend fun fetch(tradeDate: LocalDate): Map<String, Float> {
        val formattedTradeDate = tradeDate.toString().replace("-", "")
        return try {
            tushare.getAdjFactor(date = formattedTradeDate).check()?.items?.asReversed().orEmpty()
                .mapNotNull { item ->
                    val tsCode = item.getOrNull(0)?.takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
                    val adj = item.getOrNull(2)?.toFloatOrNull() ?: return@mapNotNull null
                    tsCode to adj
                }
                .toMap()
        } catch (e: Exception) {
            logger.error("加载交易日 adj_factor 失败 $tradeDate: ${e.message}")
            emptyMap()
        }
    }
}

private data class CachedRealtimeDailyFact(
    val tradeDate: LocalDate,
    val candle: Candle,
    val cachedAtMs: Long,
)

private data class CachedTradeDateAdjFactors(
    val tradeDate: LocalDate,
    val adjByCode: Map<String, Float>,
    val loadedAtMs: Long,
)

private const val RT_K_BATCH_SIZE = 200



/**
 * 基于 Tushare `rt_min_daily` 的实时分钟线加载适配器。
 *
 * 输出的是“当日分钟窗口全量”，用于直接覆盖 Provider 的实时轨道。
 */
class TushareRealtimeMinuteCandleLoader : RealtimeMinuteCandleLoader {
    private val logger by logger("TushareRealtimeMinuteCandleLoader")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun load(request: RealtimeMinuteCandleRequest): List<Candle> {
        require(request.period.isMinutePeriod()) {
            "实时分钟线加载端口只支持分钟周期，当前收到: ${request.period}"
        }

        return try {
            val response = tushare.getRtMinDaily(
                tsCode = request.tsCode,
                freq = request.period.toMinuteFreq().uppercase()
            )
            val data = response.check() ?: return emptyList()

            data.toColumns(sortKey = "time").map { col ->
                val tradeTime = col provides "time"
                val open = (col provides "open").toFloatOrNull() ?: 0f
                val close = (col provides "close").toFloatOrNull() ?: 0f
                val high = (col provides "high").toFloatOrNull() ?: 0f
                val low = (col provides "low").toFloatOrNull() ?: 0f
                val volume = ((col provides "vol").toFloatOrNull() ?: 0f) / 100f
                val amount = (col provides "amount").toFloatOrNull() ?: 0f
                val date = tradeTime.parseTushareTradeDate()

                Candle(
                    id = Uuid.random(),
                    tsCode = request.tsCode,
                    date = date,
                    tradeTime = tradeTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    adj = 1f,
                    openQfq = open,
                    closeQfq = close,
                    highQfq = high,
                    lowQfq = low,
                    volume = volume,
                    volumeQfq = volume,
                    turnoverReal = amount * 1000f,
                    pe = 0f,
                    peTtm = 0f,
                    pb = 0f,
                    ps = 0f,
                    psTtm = 0f,
                    mvTotal = 0f,
                    mvCirc = 0f
                )
            }
        } catch (e: Exception) {
            logger.error("加载实时分钟线失败 ${request.tsCode} ${request.period}: ${e.message}")
            emptyList()
        }
    }
}

private fun CandlePeriod.isMinutePeriod(): Boolean = when (this) {
    CandlePeriod.MIN_5,
    CandlePeriod.MIN_15,
    CandlePeriod.MIN_30,
    CandlePeriod.MIN_60 -> true

    CandlePeriod.DAY,
    CandlePeriod.WEEK,
    CandlePeriod.MONTH -> false
}

/**
 * 解析 Tushare 返回的日期/时间字段。
 *
 * 支持三种旧链路中已经出现的格式：
 * - `yyyy-MM-dd HH:mm:ss`
 * - `yyyy-MM-dd`
 * - `yyyyMMdd`
 *
 * 解析失败时统一回退到哨兵日期，保证新架构在远端脏数据场景下不会崩溃。
 */
private fun String.parseTushareTradeDate(): LocalDate {
    return try {
        when {
            contains(" ") -> LocalDate.parse(substringBefore(" ").take(10))
            contains("-") -> LocalDate.parse(this)
            length == 8 -> LocalDate(
                substring(0, 4).toInt(),
                substring(4, 6).toInt(),
                substring(6, 8).toInt()
            )

            else -> LocalDate.parse("1970-01-01")
        }
    } catch (_: Exception) {
        LocalDate.parse("1970-01-01")
    }
}

/**
 * 把 Tushare 周/月接口的一行数据标准化成统一 `Candle`。
 *
 * 周/月历史不落库，因此这里的映射质量直接决定了 Provider 最终看到的 H 轨道。
 * 映射规则严格沿用旧 `WeeklyMonthlyCandleService`：
 * - `trade_date` 作为交易日
 * - `vol` 直接作为量
 * - `amount` 从千元换算成元
 * - 周/月没有盘中时间戳，因此 `tradeTime = null`
 */
@OptIn(ExperimentalUuidApi::class)
private fun Column.toPeriodicCandle(tsCode: String): Candle {
    val tradeDate = provides("trade_date").parseTushareTradeDate()
    val open = provides("open").toFloatOrNull() ?: 0f
    val close = provides("close").toFloatOrNull() ?: 0f
    val high = provides("high").toFloatOrNull() ?: 0f
    val low = provides("low").toFloatOrNull() ?: 0f
    val volume = provides("vol").toFloatOrNull() ?: 0f
    val amount = provides("amount").toFloatOrNull() ?: 0f
    return Candle(
        id = Uuid.random(),
        tsCode = tsCode,
        date = tradeDate,
        tradeTime = null,
        open = open,
        high = high,
        low = low,
        close = close,
        adj = 1f,
        openQfq = open,
        closeQfq = close,
        highQfq = high,
        lowQfq = low,
        volume = volume,
        volumeQfq = volume,
        turnoverReal = amount * 1000f,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )
}

private fun CandlePeriod.toMinuteFreq(): String = when (this) {
    CandlePeriod.MIN_5 -> "5min"
    CandlePeriod.MIN_15 -> "15min"
    CandlePeriod.MIN_30 -> "30min"
    CandlePeriod.MIN_60 -> "60min"
    else -> error("分钟频度转换只支持分钟周期，当前收到: $this")
}

/**
 * 为“最近 N 根分钟线”场景反推出一个足够保守的开始时间。
 *
 * 设计目标：
 * 1. 优先满足 Provider 对历史窗口数量的要求，而不是最小化请求范围
 * 2. 保持与旧 `MinuteCandleService` 一致的语义，避免初始化窗口变短
 * 3. 对周末和节假日预留额外缓冲，不把返回数量压在理论最小值上
 */
private fun calculateSafeHistoricalMinuteStartTime(
    limit: Int,
    period: CandlePeriod,
    endTime: String?
): String {
    val endDateTime = endTime?.let {
        runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrNull()
    } ?: LocalDateTime.now()

    val periodMinutes = when (period) {
        CandlePeriod.MIN_5 -> 5
        CandlePeriod.MIN_15 -> 15
        CandlePeriod.MIN_30 -> 30
        CandlePeriod.MIN_60 -> 60
        else -> error("安全回看窗口只支持分钟周期，当前收到: $period")
    }

    /**
     * A 股单日有效交易时长约为 240 分钟。
     * 这里沿用旧实现的思路：
     * - 按目标 bar 数量估算理论所需交易日
     * - 再乘以额外缓冲，覆盖周末、节假日和数据缺口
     */
    val estimatedDays = ((limit.toDouble() * periodMinutes) / 240.0 * 1.6).toLong() + 2
    return endDateTime.minusDays(estimatedDays).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

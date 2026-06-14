package org.shiroumi.server.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.HistoricalDailyCandleRequest
import model.dataprovider.HistoricalMinuteCandleRequest
import model.dataprovider.HistoricalWeeklyMonthlyCandleRequest
import model.dataprovider.RealtimeDailyCandleRequest
import model.dataprovider.RealtimeMinuteCandleRequest
import org.shiroumi.database.stock.StockReader
import org.shiroumi.server.dataprovider.adapter.AuthoritativeRealtimeDailyCandleLoader
import org.shiroumi.server.dataprovider.adapter.StockReaderHistoricalDailyCandleLoader
import org.shiroumi.server.dataprovider.adapter.TushareHistoricalMinuteCandleFetcher
import org.shiroumi.server.dataprovider.adapter.TushareHistoricalMonthlyCandleFetcher
import org.shiroumi.server.dataprovider.adapter.TushareHistoricalWeeklyCandleFetcher
import org.shiroumi.server.dataprovider.adapter.TushareRealtimeMinuteCandleLoader
import utils.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `server.data.api` 是新数据层访问 K 线外部数据的唯一入口。
 *
 * 这一层的职责非常刻意地收窄为三件事：
 * 1. 把不同来源的 K 线读取能力收口成统一任务模型
 * 2. 为每个接口提供独立的任务队列与限流器
 * 3. 把结果以标准化 `List<Candle>` 回调给 snapshot 层
 *
 * 它明确不负责：
 * - 任何订阅者管理
 * - 任何缓存
 * - 任何 payload 组装
 *
 * 迁移语义：
 * - 它对标替代旧 Candle 主链中散落在 `MinuteCandleService`、`MarketDailySnapshotService`
 *   和 `CandleDataAdapters` 上层调用点的“直接打外部接口”行为
 * - 旧 adapter 仍可作为底层抓取能力被复用，但新的上层入口必须只走这一层
 */
// open 仅为支撑同模块单测：snapshot 层依赖注入这一层，测试通过子类覆写 submit 统计回填提交次数，
// 并验证 submit 成功后 callback 不会同步触发。生产路径行为不变。
open class CandleApiLayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val historicalDailyLoader: StockReaderHistoricalDailyCandleLoader = StockReaderHistoricalDailyCandleLoader(),
    private val realtimeDailyLoader: AuthoritativeRealtimeDailyCandleLoader = AuthoritativeRealtimeDailyCandleLoader(),
    private val historicalMinuteLoader: TushareHistoricalMinuteCandleFetcher = TushareHistoricalMinuteCandleFetcher(),
    private val realtimeMinuteLoader: TushareRealtimeMinuteCandleLoader = TushareRealtimeMinuteCandleLoader(),
    private val weeklyLoader: TushareHistoricalWeeklyCandleFetcher = TushareHistoricalWeeklyCandleFetcher(),
    private val monthlyLoader: TushareHistoricalMonthlyCandleFetcher = TushareHistoricalMonthlyCandleFetcher()
) {
    private val logger by logger("CandleApiLayer")

    private val channels = mapOf(
        InterfaceId.DB_DAILY to ApiChannel(
            interfaceId = InterfaceId.DB_DAILY,
            scope = scope,
            tokenBucket = TokenBucket.unlimited(),
            executor = ::executeDbDaily
        ),
        InterfaceId.RT_K to ApiChannel(
            interfaceId = InterfaceId.RT_K,
            scope = scope,
            tokenBucket = TokenBucket.perMinute(50),
            executor = ::executeRtK
        ),
        InterfaceId.STK_MINS to ApiChannel(
            interfaceId = InterfaceId.STK_MINS,
            scope = scope,
            tokenBucket = TokenBucket.unlimited(),
            executor = ::executeStkMins
        ),
        InterfaceId.RT_MIN_DAILY to ApiChannel(
            interfaceId = InterfaceId.RT_MIN_DAILY,
            scope = scope,
            tokenBucket = TokenBucket.unlimited(),
            executor = ::executeRtMinDaily
        ),
        InterfaceId.WEEKLY to ApiChannel(
            interfaceId = InterfaceId.WEEKLY,
            scope = scope,
            tokenBucket = TokenBucket.unlimited(),
            executor = ::executeWeekly
        ),
        InterfaceId.MONTHLY to ApiChannel(
            interfaceId = InterfaceId.MONTHLY,
            scope = scope,
            tokenBucket = TokenBucket.unlimited(),
            executor = ::executeMonthly
        )
    )

    fun start() {
        channels.values.forEach { it.start() }
    }

    /**
     * 向指定接口提交任务。
     *
     * 正确性约束：
     * - 提交成功不代表任务一定执行成功，只代表任务进入了对应接口的队列
     * - 若接口限流器当前无令牌，任务会在 channel worker 中被直接丢弃并回调失败
     * - snapshot 层必须把“丢弃”视为预期行为，下一次读取时继续尝试，而不是在这里做阻塞等待
     */
    open fun submit(task: ApiTask): Boolean {
        val channel = channels[task.interfaceId]
            ?: error("未知 K 线接口: ${task.interfaceId}")
        return channel.submit(task)
    }

    private suspend fun executeDbDaily(params: TaskParams): List<Candle> {
        val request = params as TaskParams.DbDailyParams
        return historicalDailyLoader.load(
            HistoricalDailyCandleRequest(
                tsCode = request.tsCode,
                limit = request.limit
            )
        )
    }

    private suspend fun executeRtK(params: TaskParams): List<Candle> = when (params) {
        is TaskParams.RtKParams.ByCodes -> realtimeDailyLoader.load(
            RealtimeDailyCandleRequest(tsCodes = params.tsCodes)
        )
        is TaskParams.RtKParams.ByWildcards -> realtimeDailyLoader.loadByWildcards(params.wildcards)
        else -> error("RT_K 接口只接受 RtKParams，当前收到: ${params::class.simpleName}")
    }

    private suspend fun executeStkMins(params: TaskParams): List<Candle> {
        val request = params as TaskParams.StkMinsParams
        require(request.period in SUPPORTED_MINUTE_PERIODS) {
            "新数据层只支持 5/15/30/60 分钟周期，当前收到: ${request.period}"
        }
        return historicalMinuteLoader.load(
            HistoricalMinuteCandleRequest(
                tsCode = request.tsCode,
                period = request.period,
                startTime = request.startTime,
                endTime = request.endTime,
                limit = request.limit
            )
        )
    }

    private suspend fun executeRtMinDaily(params: TaskParams): List<Candle> {
        val request = params as TaskParams.RtMinDailyParams
        require(request.period in SUPPORTED_MINUTE_PERIODS) {
            "新数据层只支持 5/15/30/60 分钟周期，当前收到: ${request.period}"
        }
        return realtimeMinuteLoader.load(
            RealtimeMinuteCandleRequest(
                tsCode = request.tsCode,
                period = request.period
            )
        )
    }

    private suspend fun executeWeekly(params: TaskParams): List<Candle> {
        val request = params as TaskParams.WeeklyMonthlyParams
        return weeklyLoader.load(
            HistoricalWeeklyMonthlyCandleRequest(
                tsCode = request.tsCode,
                period = CandlePeriod.WEEK,
                startDate = request.startDate,
                endDate = request.endDate,
                limit = request.limit
            )
        )
    }

    private suspend fun executeMonthly(params: TaskParams): List<Candle> {
        val request = params as TaskParams.WeeklyMonthlyParams
        return monthlyLoader.load(
            HistoricalWeeklyMonthlyCandleRequest(
                tsCode = request.tsCode,
                period = CandlePeriod.MONTH,
                startDate = request.startDate,
                endDate = request.endDate,
                limit = request.limit
            )
        )
    }

    companion object {
        val SUPPORTED_MINUTE_PERIODS = setOf(
            CandlePeriod.MIN_5,
            CandlePeriod.MIN_15,
            CandlePeriod.MIN_30,
            CandlePeriod.MIN_60
        )
    }
}

enum class InterfaceId {
    DB_DAILY,
    RT_K,
    STK_MINS,
    RT_MIN_DAILY,
    WEEKLY,
    MONTHLY
}

sealed interface TaskParams {
    data class DbDailyParams(
        val tsCode: String,
        val limit: Int = 500
    ) : TaskParams

    sealed interface RtKParams : TaskParams {
        /**
         * 按指定 ts_code 列表精确拉取实时日 K。
         * 用于页面可见股票的小批量优先刷新场景。
         */
        data class ByCodes(val tsCodes: List<String>) : RtKParams

        /**
         * 用通配符一次性拉取全市场实时日 K。
         * 一次接口调用即可覆盖全市场，对应 rt_k 接口的 50/min 物理上限下唯一可持续的全市场轮询方式。
         */
        data class ByWildcards(val wildcards: List<String>) : RtKParams
    }

    data class RtMinDailyParams(
        val tsCode: String,
        val period: CandlePeriod
    ) : TaskParams

    data class StkMinsParams(
        val tsCode: String,
        val period: CandlePeriod,
        val startTime: String? = null,
        val endTime: String? = null,
        val limit: Int = 500
    ) : TaskParams

    data class WeeklyMonthlyParams(
        val tsCode: String,
        val startDate: String? = null,
        val endDate: String? = null,
        val limit: Int = 500
    ) : TaskParams
}

data class ApiTask(
    val interfaceId: InterfaceId,
    val params: TaskParams,
    val callback: (Result<List<Candle>>) -> Unit,
    val requestId: String = "${interfaceId.name}-${System.nanoTime()}",
    val submittedAt: Long = System.currentTimeMillis()
)

class ApiChannel(
    private val interfaceId: InterfaceId,
    private val scope: CoroutineScope,
    private val tokenBucket: TokenBucket,
    private val executor: suspend (TaskParams) -> List<Candle>,
    channelCapacity: Int = Channel.BUFFERED
) {
    private val logger by logger("ApiChannel-${interfaceId.name}")
    private val started = AtomicInteger(0)
    private val taskChannel = Channel<ApiTask>(channelCapacity)

    /**
     * 每个接口只启动一个 worker。
     *
     * 这里故意不做多 worker 并发消费，因为 `rt_k` 这类接口的物理瓶颈在接口额度，
     * 盲目并发只会让限流后的丢弃更加混乱，也会让同一接口的日志与行为更难排查。
     */
    fun start() {
        if (!started.compareAndSet(0, 1)) return
        scope.launch {
            for (task in taskChannel) {
                if (!tokenBucket.tryAcquire()) {
                    logger.info("接口限流丢弃任务: interface=$interfaceId requestId=${task.requestId}")
                    task.callback(Result.failure(ApiRateLimitException(interfaceId)))
                    continue
                }
                val result = runCatching {
                    executor(task.params)
                }
                task.callback(result)
            }
        }
    }

    fun submit(task: ApiTask): Boolean = taskChannel.trySend(task).isSuccess
}

class TokenBucket private constructor(
    permitsPerMinute: Int
) {
    private val unlimited = permitsPerMinute <= 0
    private val capacity = if (unlimited) Int.MAX_VALUE else 1
    private val tokens = AtomicInteger(capacity)
    private val lastRefillAt = AtomicLong(System.currentTimeMillis())
    private val refillIntervalMs = if (unlimited) Long.MAX_VALUE else 60_000L / permitsPerMinute

    /**
     * 这里采用“无令牌直接失败”的严格语义。
     *
     * 这是本次架构里非常关键的正确性约束：
     * - 目标是暴露真实的外部接口物理上限
     * - 不是通过本地排队把压力藏起来
     *
     * 否则一旦热点时段产生堆积，snapshot 读取到的数据就会变成“很晚但看起来成功”的旧数据，
     * 这比明确丢弃再重试更难诊断。
     */
    fun tryAcquire(): Boolean {
        if (unlimited) return true
        refill()
        return tokens.getAndUpdate { current ->
            if (current > 0) current - 1 else current
        } > 0
    }

    private fun refill() {
        if (unlimited) return
        val now = System.currentTimeMillis()
        val last = lastRefillAt.get()
        val elapsed = now - last
        val toAdd = (elapsed / refillIntervalMs).toInt()
        if (toAdd <= 0) return
        if (lastRefillAt.compareAndSet(last, now)) {
            tokens.updateAndGet { current ->
                (current + toAdd).coerceAtMost(capacity)
            }
        }
    }

    companion object {
        fun unlimited(): TokenBucket = TokenBucket(-1)
        fun perMinute(permitsPerMinute: Int): TokenBucket = TokenBucket(permitsPerMinute)
    }
}

class ApiRateLimitException(interfaceId: InterfaceId) :
    IllegalStateException("K线接口限流: ${interfaceId.name}")

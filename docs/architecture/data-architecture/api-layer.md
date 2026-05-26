# apiLayer（数据接口层）

## 1. 定位

apiLayer 是**K 线数据唯一与外部数据源交互的层**。它只封装 K 线相关的外部接口（Tushare、数据库），提供统一的任务队列 + 令牌桶限流。非 K 线数据的外部调用不经过 apiLayer。

- **任务队列**：每个接口通过协程 Channel 接收任务，无任务时自动阻塞
- **令牌桶限流**：每个接口独立配置，无令牌时任务自动丢弃
- **原始数据返回**：只负责调用接口并返回标准化 `Candle` 列表，不缓存、不组装、不推送

## 2. 核心设计原则

1. **接口即边界**：一个外部接口对应一个 apiLayer 封装单元，彼此独立
2. **任务式驱动**：snapshot 或启动流程下发任务 → Channel 接收 → 令牌桶检查 → 执行调用
3. **无令牌即丢弃**：不是排队等待，而是直接丢弃任务。防止任务堆积导致雪崩
4. **无状态**：apiLayer 不维护业务缓存状态，调用结果直接返回给任务发起方
5. **仅限 K 线**：apiLayer 不封装非 K 线接口（如情绪计算、策略状态等）

> 注：底层 adapter 可以为了外部接口一致性维护极短 TTL 的事实复用缓存，例如
> `AuthoritativeRealtimeDailyCandleLoader` 在 1 秒窗口内复用同一批 `rt_k` 事实，避免
> K 线 / 情绪 / 因子消费者在同一刷新窗口看到不同实时日线。此类缓存必须是
> adapter-local、受 `tradeDate + TTL` 主动清理约束，不承担 snapshot 的业务缓存职责。

## 3. 接口清单与限流配置

| 接口标识 | 对应 Tushare 接口 | 功能 | 令牌桶配置 | 说明 |
|----------|------------------|------|-----------|------|
| `rt_k` | `rt_k` | 实时日线 | **50 次/分钟** | 全市场刷新使用通配符；页面可见股票优先刷新使用小批量代码 |
| `rt_min_daily` | `rt_min_daily` | 实时分钟 | **∞（不限流）** | 一次性提取某股票当日某周期全部 K 线 |
| `stk_mins` | `stk_mins` | 历史分钟 | **∞（不限流）** | 不包含当日数据。每只股票不出缓存只调用一次 |
| `weekly` | `weekly` | 历史周线 | **∞（不限流）** | 理论不出缓存一次性调用 |
| `monthly` | `monthly` | 历史月线 | **∞（不限流）** | 理论不出缓存一次性调用 |
| `db_daily` | 数据库 `stock_daily_data` | 历史日线 | **∞（不限流）** | 服务器启动时及盘后追平后从数据库加载 |

## 4. rt_k 全市场刷新策略

rt_k 是日区实时数据的唯一来源，其调用特性如下：

- **请求方式**：全市场刷新使用通配符集合 `6*.SH,3*.SZ,688*.SH,0*.SZ,8*.BJ`；小范围优先刷新仍支持多代码批量请求
- **全市场覆盖**：一次 `rt_k` 调用覆盖 A 股主板/创业板/科创板/北交所实时日线事实
- **限频约束**：50 次/分钟 = 约每 1.2 秒 1 个令牌；全市场刷新每次只消耗 1 个令牌

**SyncLooper 调度策略**：
- SyncLooper 将实时拉取和推送 drain 拆成两条节奏：全市场通配符 rt_k 每 1.2 秒提交一次，provider / listener 仍每秒 drain 变更 key
- 若上一轮 rt_k 仍在执行，会跳过本轮，避免并发占用同一接口配额
- `09:15-11:30`、`13:00-15:00` 为盘中实时轮询阶段，Ktor DAY snapshot 以通配符结果覆盖最新一根日线
- `11:30-13:00`、`15:00-盘后批量日线更新完成 U` 为历史 + 固定单次实时拼接阶段，只执行一次成功的全市场 rt_k pass，用于把尚未落库的当天事实拼入 DAY 快照
- `U(t-1)-t日09:15` 以及 `U(t)` 之后为纯历史阶段，DAY 快照以数据库日线为准
- Provider 推送不再每秒扫描全部订阅，而是消费 snapshot 版本推进产生的变更 key；rt_k 拉取节奏与 WebSocket 推送节奏由 SyncLooper 分别编排

## 5. 核心结构

### 5.1 任务定义

```kotlin
data class ApiTask(
    val interfaceId: String,        // 接口标识：rt_k / rt_min_daily / stk_mins 等
    val params: TaskParams,         // 接口调用参数
    val requestId: String,          // 任务唯一标识（用于 tracing）
    val submittedAt: Long,          // 任务提交时间戳
    val callback: (Result<List<Candle>>) -> Unit  // 结果回调
)

sealed interface TaskParams {
    sealed interface RtKParams : TaskParams {
        data class ByCodes(
            val tsCodes: List<String>      // 页面可见股票等小批量优先刷新
        ) : RtKParams

        data class ByWildcards(
            val wildcards: List<String>    // 全市场通配符，如 6*.SH,3*.SZ,688*.SH,0*.SZ,8*.BJ
        ) : RtKParams
    }

    data class RtMinDailyParams(
        val tsCode: String,
        val period: CandlePeriod    // MIN_5 / MIN_15 / MIN_30 / MIN_60
    ) : TaskParams()

    data class StkMinsParams(
        val tsCode: String,
        val period: CandlePeriod,
        val startDate: String?,     // 格式：yyyy-MM-dd HH:mm:ss
        val endDate: String?        // 格式：yyyy-MM-dd HH:mm:ss
    ) : TaskParams()

    data class WeeklyMonthlyParams(
        val tsCode: String,
        val period: CandlePeriod,   // WEEK / MONTH
        val startDate: String?,     // 格式：yyyyMMdd
        val endDate: String?        // 格式：yyyyMMdd
    ) : TaskParams()

    data class DbDailyParams(
        val tsCode: String,
        val limit: Int = 500
    ) : TaskParams()
}
```

### 5.2 接口通道（ApiChannel）

```kotlin
class ApiChannel(
    val interfaceId: String,
    private val tokenBucket: TokenBucket,
    private val executor: suspend (TaskParams) -> List<Candle>,
    private val scope: CoroutineScope,
    channelCapacity: Int = Channel.BUFFERED
) {
    private val taskChannel = Channel<ApiTask>(capacity = channelCapacity)

    fun start() {
        scope.launch {
            for (task in taskChannel) {
                if (!tokenBucket.tryAcquire()) {
                    task.callback(Result.failure(ApiRateLimitException(interfaceId)))
                    continue
                }
                val result = runCatching { executor(task.params) }
                task.callback(result)
            }
        }
    }

    fun submit(task: ApiTask): Boolean {
        return taskChannel.trySend(task).isSuccess
    }
}
```

### 5.3 令牌桶（TokenBucket）

```kotlin
class TokenBucket(
    val permitsPerMinute: Int,      // 每分钟令牌数，-1 表示无限
    val capacity: Int = 1           // 桶容量，默认 1（禁止突发）
) {
    private val unlimited = permitsPerMinute < 0
    private val tokens = AtomicInteger(capacity)
    private val lastRefillAt = AtomicLong(System.currentTimeMillis())

    fun tryAcquire(): Boolean {
        if (unlimited) return true
        refill()
        return tokens.getAndUpdate { if (it > 0) it - 1 else it } > 0
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillAt.get()
        val intervalMs = 60_000L / permitsPerMinute
        val elapsed = now - last
        val toAdd = (elapsed / intervalMs).toInt()
        if (toAdd > 0 && lastRefillAt.compareAndSet(last, now)) {
            tokens.updateAndGet { (it + toAdd).coerceAtMost(capacity) }
        }
    }
}
```

## 6. apiLayer 管理器

```kotlin
class ApiLayerManager(
    private val channels: Map<String, ApiChannel>
) {
    fun submit(interfaceId: String, task: ApiTask): Boolean {
        val channel = channels[interfaceId] 
            ?: error("未知接口: $interfaceId")
        return channel.submit(task)
    }

    fun startAll() {
        channels.values.forEach { it.start() }
    }
}
```

## 7. 各接口执行器语义

### 7.1 rt_k（实时日线）

```kotlin
suspend fun executeRtK(params: RtKParams): List<Candle> = when (params) {
    is RtKParams.ByWildcards -> realtimeDailyLoader.loadByWildcards(params.wildcards)
    is RtKParams.ByCodes -> realtimeDailyLoader.load(RealtimeDailyCandleRequest(params.tsCodes))
}
```

### 7.2 rt_min_daily（实时分钟）

```kotlin
suspend fun executeRtMinDaily(params: RtMinDailyParams): List<Candle> {
    val response = tushare.getRtMinDaily(
        tsCode = params.tsCode,
        freq = params.period.toFreq().uppercase()
    )
    return response.toCandles(tsCode = params.tsCode)
}
```

### 7.3 stk_mins（历史分钟）

```kotlin
suspend fun executeStkMins(params: StkMinsParams): List<Candle> {
    val response = tushare.getStkMins(
        tsCode = params.tsCode,
        freq = params.period.toFreq(),
        startDate = params.startDate,
        endDate = params.endDate
    )
    return response.toCandles(tsCode = params.tsCode)
}
```

**关键约束**：`stk_mins` 返回的数据不包含当日数据。历史与实时数据在时间维度上无重叠，合并时直接拼接即可。

### 7.4 weekly / monthly（周/月线）

```kotlin
suspend fun executeWeeklyMonthly(params: WeeklyMonthlyParams): List<Candle> {
    val response = when (params.period) {
        CandlePeriod.WEEK -> tushare.getWeeklyCandles(params.tsCode, params.startDate, params.endDate)
        CandlePeriod.MONTH -> tushare.getMonthlyCandles(params.tsCode, params.startDate, params.endDate)
    }
    return response.toCandles(tsCode = params.tsCode)
}
```

### 7.5 db_daily（历史日线）

```kotlin
suspend fun executeDbDaily(params: DbDailyParams): List<Candle> {
    return StockReader.getStockHistory(params.tsCode, params.limit)
}
```

## 8. 任务生命周期

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   任务提交   │────►│  Channel    │────►│  令牌桶检查  │────►│   执行调用   │
│ (snapshot   │     │  (队列缓冲)  │     │ (无令牌丢弃) │     │  (外部接口)  │
│  或启动流程) │     │             │     │             │     │             │
└─────────────┘     └─────────────┘     └──────┬──────┘     └──────┬──────┘
                                               │                   │
                                               ▼                   ▼
                                        ┌─────────────┐     ┌─────────────┐
                                        │   任务丢弃   │     │  回调结果   │
                                        │ (记录日志)  │     │ (snapshot   │
                                        └─────────────┘     │  接收写入)  │
                                                            └─────────────┘
```

## 9. 故障处理

### 9.1 接口调用失败

- 执行器内部捕获异常，通过 `callback(Result.failure(e))` 返回错误
- snapshot 层收到失败结果后，保持当前缓存值不变
- 下次 DATA_SYNC 或缓存未命中时重新下发任务

### 9.2 Channel 满

- `taskChannel.trySend()` 返回 false 时，任务未进入队列
- 调用方（snapshot）应记录日志，下次需要时重新提交
- Channel 容量应根据接口特性配置

### 9.3 令牌桶无余量

- 任务进入 Channel 后，消费时检查令牌桶
- 无令牌则立即丢弃，回调返回 `ApiRateLimitException`
- **禁止**让任务在 Channel 中排队等待令牌，防止堆积

# Snapshot（快照缓存层）

## 1. 定位

Snapshot **只负责 K 线数据的缓存和周期驱动**。它按周期分类缓存 K 线，通过 SyncLooper 驱动订阅更新。非 K 线数据不经过 Snapshot。

具体职责：
1. **按周期分类缓存** K 线数据（日区、5分区、15分区、30分区、60分区、周区、月区）
2. **内置 SyncLooper**，固定每秒向 K 线 Provider 广播 `DATA_SYNC` 信号
3. **管理数据缺口**：K 线 Provider 请求的数据不在缓存中时，向 apiLayer 下发数据任务
4. **接收 apiLayer 返回**，将新数据写入对应分区缓存
5. **盘后日区历史刷新**：完成盘后追平后，全量重新加载 db_daily

Snapshot **不**组装 topic 数据、**不**直接调用外部接口、**不**管理 WebSocket 会话、**不**缓存非 K 线数据。

## 2. 核心结构

### 2.1 周期分区缓存

```kotlin
class PeriodCache {
    // 日区：全量常驻，Map<tsCode, DayKLine[]>
    val dayData: ConcurrentHashMap<String, List<Candle>>

    // x分区：历史数据，不过期缓存，Map<tsCode, HistoricalKLine[]>
    val min5History:  ConcurrentHashMap<String, List<Candle>>
    val min15History: ConcurrentHashMap<String, List<Candle>>
    val min30History: ConcurrentHashMap<String, List<Candle>>
    val min60History: ConcurrentHashMap<String, List<Candle>>

    // x分区：实时数据，LRU 缓存，容量 1000
    val min5Realtime:  LRUCache<String, List<Candle>>(capacity = 1000)
    val min15Realtime: LRUCache<String, List<Candle>>(capacity = 1000)
    val min30Realtime: LRUCache<String, List<Candle>>(capacity = 1000)
    val min60Realtime: LRUCache<String, List<Candle>>(capacity = 1000)

    // 周区/月区：LRU 缓存，容量 1000
    val weekData:  LRUCache<String, List<Candle>>(capacity = 1000)
    val monthData: LRUCache<String, List<Candle>>(capacity = 1000)
}
```

### 2.2 分区选择器

```kotlin
fun PeriodCache.select(period: CandlePeriod, dataType: DataType): CacheContainer<List<Candle>> =
    when (period to dataType) {
        CandlePeriod.DAY      to DataType.MERGED    -> dayData.asCache()
        CandlePeriod.MIN_5    to DataType.HISTORY   -> min5History.asCache()
        CandlePeriod.MIN_5    to DataType.REALTIME  -> min5Realtime
        CandlePeriod.MIN_15   to DataType.HISTORY   -> min15History.asCache()
        CandlePeriod.MIN_15   to DataType.REALTIME  -> min15Realtime
        CandlePeriod.MIN_30   to DataType.HISTORY   -> min30History.asCache()
        CandlePeriod.MIN_30   to DataType.REALTIME  -> min30Realtime
        CandlePeriod.MIN_60   to DataType.HISTORY   -> min60History.asCache()
        CandlePeriod.MIN_60   to DataType.REALTIME  -> min60Realtime
        CandlePeriod.WEEK     to DataType.MERGED    -> weekData
        CandlePeriod.MONTH    to DataType.MERGED    -> monthData
        else -> error("不支持的分区: $period / $dataType")
    }
```

### 2.3 同步循环器（SyncLooper）

SyncLooper 只驱动 K 线数据的周期更新，不感知非 K 线 Provider。

```kotlin
class SyncLooper(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1_000L,      // provider drain 固定每秒一次
    private val dayRealtimeIntervalMs: Long = 1_200L, // rt_k 贴合 50/min
    private val klineProvider: KlineDataProvider, // K 线 Provider（按 Topic 唯一实例）
    private val dayCache: DayCacheManager,       // 日区缓存管理器
) {
    private var drainJob: Job? = null
    private var realtimeJob: Job? = null

    fun start() {
        realtimeJob = scope.launch {
            while (isActive) {
                // 1. 驱动日区实时更新（一次通配符 rt_k 覆盖全市场）
                dayCache.scheduleRealtimeUpdate()
                delay(dayRealtimeIntervalMs)
            }
        }

        drainJob = scope.launch {
            while (isActive) {
                // 2. 只把 snapshot 中真实发生版本变化的 key 交给 Provider
                klineProvider.onDataSync(snapshot.drainChangedKeys())
                
                delay(intervalMs)
            }
        }
    }
}
```

> SyncLooper 只持有 K 线 Provider 的引用。非 K 线 Provider 自行决定刷新节奏，不接收 DATA_SYNC。

Snapshot 在 K 线版本推进时登记变更 key，SyncLooper 按固定节奏 drain 这些 key。这样 Provider 不需要每秒扫描全部订阅，也不需要直接注册 snapshot 回调；Snapshot 仍不感知 WebSocket session，Provider 仍只负责 topic 映射与投影。

## 3. 各分区详细策略

### 3.1 日区（DAY）

**数据结构**：
```kotlin
class DayCacheManager {
    // 历史数据：全量常驻 Map
    val historical: ConcurrentHashMap<String, List<Candle>>
    
    // 实时数据：单条当日最新（与历史分开存储，便于覆盖更新）
    val realtime: ConcurrentHashMap<String, Candle>
    
    // rt_k 通配符调用：一次覆盖全市场
    private val wholeMarketWildcards = listOf("6*.SH", "3*.SZ", "688*.SH", "0*.SZ", "8*.BJ")
}
```

**历史数据加载**：
- 服务器启动时，从数据库加载全市场日线历史数据
- 通过 `db_daily` 接口（apiLayer）批量加载
- 加载完成后填入 `historical` Map

**盘后历史刷新**：
- 每日收盘后，完成盘后数据追平（自动或手动）
- 追平完成后，**立刻全量重新加载一次 db_daily**
- 将新的历史数据（包含当日收盘）覆盖写入 `historical` Map
- 清空 `realtime` Map（当日数据已进入历史，盘中实时数据不再需要）

**实时数据更新**：
- SyncLooper 每 1.2 秒调用 `dayCache.scheduleRealtimeUpdate()`，贴合 rt_k 50/min 限额
- `09:15-11:30`、`13:00-15:00` 为连续实时阶段，用通配符集合一次性向 apiLayer 的 `rt_k` 通道提交全市场任务
- `11:30-13:00`、`15:00-盘后批量日线更新完成 U` 为历史 + 固定单次实时拼接阶段，按阶段 key 执行一次成功的全市场 rt_k pass
- `U(t-1)-t日09:15` 以及 `U(t)` 之后为纯历史阶段，不再拉取 rt_k
- apiLayer 返回结果后，逐条更新 `realtime` Map
- 连续实时阶段若上一轮通配符调用仍在执行，会跳过本轮，避免同一接口并发占用配额
- 全市场一轮更新约 1.2 秒（一次通配符调用消耗 1 个令牌）

**Provider 读取**：
```kotlin
fun DayCacheManager.getMerged(tsCode: String): List<Candle> {
    val hist = historical[tsCode].orEmpty()
    val rt = realtime[tsCode]
    return if (rt != null) {
        hist.filterNot { it.date == rt.date } + rt
    } else {
        hist
    }
}
```

### 3.2 x分区历史（MIN_5/15/30/60）

**缓存策略**：不过期缓存（`ConcurrentHashMap`）

```kotlin
class MinuteHistoryCache {
    val caches: Map<CandlePeriod, ConcurrentHashMap<String, List<Candle>>> = mapOf(
        CandlePeriod.MIN_5  to ConcurrentHashMap(),
        CandlePeriod.MIN_15 to ConcurrentHashMap(),
        CandlePeriod.MIN_30 to ConcurrentHashMap(),
        CandlePeriod.MIN_60 to ConcurrentHashMap()
    )
}
```

**数据填充**：
- 服务器启动时不预加载（避免启动压力）
- K 线 Provider 首次请求某股票的 x分区历史数据时：
  1. 检查 `caches[period][tsCode]` 是否存在
  2. 若存在，直接返回
  3. 若不存在，向 apiLayer 的 `stk_mins` 通道提交任务
  4. apiLayer 返回后写入缓存，下次直接命中

**关键约束**：`stk_mins` 返回的数据**不包含当日数据**，与实时数据无时间重叠。

**理论调用次数**：每只股票每个周期不出缓存只需调用一次。

### 3.3 x分区实时（MIN_5/15/30/60）

**缓存策略**：LRU 缓存，容量 1000

```kotlin
class MinuteRealtimeCache {
    val caches: Map<CandlePeriod, LRUCache<String, List<Candle>>> = mapOf(
        CandlePeriod.MIN_5  to LRUCache(1000),
        CandlePeriod.MIN_15 to LRUCache(1000),
        CandlePeriod.MIN_30 to LRUCache(1000),
        CandlePeriod.MIN_60 to LRUCache(1000)
    )
}
```

**数据读取**：
- K 线 Provider 在 `DATA_SYNC` 信号触发时请求数据
- snapshot 先查 LRU：
  - **命中**：直接返回缓存数据
  - **未命中**：向 apiLayer 的 `rt_min_daily` 通道提交任务，本次返回 null；任务返回后写入 LRU，下次 DATA_SYNC 命中

**LRU 淘汰**：
- 当某周期缓存超过 1000 只股票时，淘汰最久未访问的股票数据
- 被淘汰的股票下次请求时重新从 apiLayer 加载

**历史与实时合并**：
```kotlin
fun readMinuteCandles(tsCode: String, period: CandlePeriod): List<Candle>? {
    val history = minuteHistory(period)[tsCode].orEmpty()
    val realtime = minuteRealtime(period).get(tsCode).orEmpty()
    // stk_mins 不含当日数据，rt_min_daily 只含当日数据，时间维度无重叠，直接拼接
    return history + realtime
}
```

### 3.4 周区（WEEK）/ 月区（MONTH）

**缓存策略**：LRU 缓存，容量 1000

```kotlin
class WeeklyMonthlyCache {
    val weekCache = LRUCache<String, List<Candle>>(1000)
    val monthCache = LRUCache<String, List<Candle>>(1000)
}
```

**数据读取**：
- K 线 Provider 在 `DATA_SYNC` 信号触发时请求数据
- snapshot 先查 LRU：
  - **命中**：直接返回
  - **未命中**：向 apiLayer 的 `weekly`/`monthly` 通道提交任务，本次返回 null；任务返回后写入 LRU

**无特殊逻辑**：不参与盘中高频刷新，由 K 线 Provider 的读取需求驱动加载。

## 4. 数据写入流程

### 4.1 apiLayer 结果回调

```kotlin
class SnapshotManager(
    private val periodCache: PeriodCache,
    private val apiLayer: ApiLayerManager
) {
    /**
     * apiLayer 任务完成后的回调入口。
     */
    fun onTaskResult(
        interfaceId: String,
        params: TaskParams,
        candles: List<Candle>
    ) {
        when (interfaceId) {
            "rt_k" -> {
                candles.forEach { candle ->
                    periodCache.dayCache.realtime[candle.tsCode] = candle
                }
            }
            "rt_min_daily" -> {
                val p = (params as RtMinDailyParams).period
                periodCache.minuteRealtime(p).put(params.tsCode, candles)
            }
            "stk_mins" -> {
                val p = (params as StkMinsParams).period
                periodCache.minuteHistory(p)[params.tsCode] = candles
            }
            "weekly" -> {
                periodCache.weekData.put((params as WeeklyMonthlyParams).tsCode, candles)
            }
            "monthly" -> {
                periodCache.monthData.put((params as WeeklyMonthlyParams).tsCode, candles)
            }
            "db_daily" -> {
                val p = params as DbDailyParams
                periodCache.dayCache.historical[p.tsCode] = candles
            }
        }
    }
}
```

### 4.2 Snapshot 读取接口（供 K 线 Provider 调用）

```kotlin
class SnapshotManager {
    /**
     * 读取 K 线数据。
     * 
     * 语义：
     * - 命中缓存：立即返回数据
     * - 未命中缓存：向 apiLayer 提交异步任务，本次返回 null
     * - 调用方（Provider）收到 null 应保持静默，等待下次 DATA_SYNC
     */
    fun readCandles(tsCode: String, period: CandlePeriod): List<Candle>? {
        return when (period) {
            CandlePeriod.DAY -> dayCache.getMerged(tsCode)
            CandlePeriod.MIN_5,
            CandlePeriod.MIN_15,
            CandlePeriod.MIN_30,
            CandlePeriod.MIN_60 -> readMinuteCandles(tsCode, period)
            CandlePeriod.WEEK -> weekData.get(tsCode)
            CandlePeriod.MONTH -> monthData.get(tsCode)
        }
    }

    /**
     * 检查指定 key 的 K 线数据是否已在缓存中。
     */
    fun hasCandles(tsCode: String, period: CandlePeriod): Boolean {
        return when (period) {
            CandlePeriod.DAY -> dayCache.historical.containsKey(tsCode)
            CandlePeriod.MIN_5,
            CandlePeriod.MIN_15,
            CandlePeriod.MIN_30,
            CandlePeriod.MIN_60 -> {
                minuteHistory(period).containsKey(tsCode) || 
                minuteRealtime(period).get(tsCode) != null
            }
            CandlePeriod.WEEK -> weekData.get(tsCode) != null
            CandlePeriod.MONTH -> monthData.get(tsCode) != null
        }
    }
}
```

## 5. 线程安全

| 分区 | 写来源 | 读来源 | 线程安全策略 |
|------|--------|--------|-------------|
| 日区历史 | 启动时 / 盘后一次性写入 | K 线 Provider 读取 | `ConcurrentHashMap` + 不可变 List |
| 日区实时 | apiLayer 回调逐条写入 | K 线 Provider 读取 | `ConcurrentHashMap`，单条原子替换 |
| 日区实时调度状态 | SyncLooper 推进 | SyncLooper 读取 | 原子 in-flight 标记，避免 rt_k 并发提交 |
| x分区历史 | apiLayer 回调写入 | K 线 Provider 读取 | `ConcurrentHashMap` + 不可变 List |
| x分区实时 | apiLayer 回调写入 | K 线 Provider 读取 | `LRUCache` 内部加锁 |
| 周/月区 | apiLayer 回调写入 | K 线 Provider 读取 | `LRUCache` 内部加锁 |

**关键约束**：
- 所有写入操作都是**替换整个 List**，而不是修改 List 中的元素
- 读操作直接返回 List 引用，无需加锁
- 这保证了读侧永远不会看到半写入状态

## 6. 启动流程

```
Server 启动
    │
    ▼
┌─────────────────┐
│  创建 Snapshot   │
│  初始化 PeriodCache│
└────────┬────────┘
         │
         ▼
┌──────────────────────────┐
│  日区历史预热             │
│  遍历全市场股票列表        │
│  向 apiLayer(db_daily)    │
│  提交批量加载任务         │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│  apiLayer 执行 db_daily   │
│  从数据库读取历史日线      │
│  回调写入 dayCache.historical│
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│  启动 SyncLooper          │
│  开始每秒 DATA_SYNC 广播   │
│  驱动日区 rt_k 批次推进    │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│  等待客户端订阅            │
│  K 线 Provider 接收 DATA_SYNC│
│  从 Snapshot 读取数据      │
│  组装并推送               │
└──────────────────────────┘
```

## 7. 盘后流程

```
盘后数据追平完成（自动或手动触发）
    │
    ▼
┌──────────────────────────┐
│  触发日区历史全量刷新      │
│  向 apiLayer(db_daily)    │
│  提交全市场重新加载任务    │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│  apiLayer 执行 db_daily   │
│  读取最新历史日线（含当日）│
│  回调覆盖 dayCache.historical│
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│  清空 dayCache.realtime   │
│  当日数据已进入历史        │
└──────────────────────────┘
```

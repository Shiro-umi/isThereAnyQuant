# Provider（数据组装层）

## 1. 定位

Provider 是**客户端与数据之间的桥梁**。每个数据订阅 Topic 对应一个 Provider 实例（按 Topic 粗分，不是按股票/周期细分）。Provider 的职责：
1. **管理订阅状态**：记录哪些客户端订阅了本 Topic，按维度分组
2. **响应 DATA_SYNC 信号**（K 线 Topic）或自行驱动刷新（非 K 线 Topic）
3. **读取数据、按 Topic 组装、推送给客户端**

**关键分层**：
- **K 线 Provider**（`CANDLE_DATA`、`INTRADAY_FACTOR`）：从 snapshot 读取 K 线数据，接收 SyncLooper 的 DATA_SYNC 驱动
- **非 K 线 Provider**（`MARKET_STATUS`、`SENTIMENT`、`STRATEGY_POSITION` 等）：自带数据源和缓存，不经过 snapshot，自行决定刷新节奏

Provider **不**直接调用外部接口。
- K 线缓存由 snapshot 负责
- 非 K 线缓存由 Provider 自己负责

### 1.1 Provider 快照与订阅边界

部分动态领域 Provider（例如尚未迁出 Ktor 的 K 线相关 Provider）会维护
`DataProviderSnapshot`，用于承载 H / R / merged 三条业务轨道。这里的边界必须保持清晰：

- `SnapshotStore` 只负责保存当前快照值，是可删除的 KV 缓存。
- `SnapshotStore` 不提供 `observe()`，不承担订阅源角色，也不保留已释放 key 的空槽位。
- `snapshotFlow` 由 Provider 自身维护，只保证在当前 Provider 实例生命周期内稳定。
- Provider `release()` 时要清空自身 `snapshotFlow`，并从 `SnapshotStore` 删除对应 key。
- WebSocket / Projection 层如需响应式更新，应订阅 Provider 暴露的 `snapshotFlow`，不能直接订阅 Store。

这个边界的业务原因是：订阅者归 WebSocket / Subscription 层管理，Provider 生命周期归
Activation / Factory / Registry 管理，Store 无法判断“旧订阅者是否已经解绑”。如果 Store
同时承担 flow 槽位，会在 Provider 释放后无法安全删除 key，长期运行会形成无界内存增长。

## 2. 抽象体系

K 线 Provider 与非 K 线 Provider 的订阅模型差异很大（K 线按 `(tsCode, period)` 细分，非 K 线不分 key），因此拆分为**两个独立的抽象基类**，互不继承，避免死代码。

### 2.1 DataProvider 抽象基类（非 K 线）

```kotlin
/**
 * 非 K 线数据 Provider 抽象基类。
 *
 * 每个非 K 线 Topic 对应一个 Provider 实例。
 * 基类收敛订阅管理、数据指纹比对和推送逻辑。
 * 子类负责：读取数据 + 按 Topic 语义组装 Payload + 决定刷新节奏。
 */
abstract class DataProvider<P : Any, D : Any>(
    val topic: WsTopic,
    protected val scope: CoroutineScope
) {
    /**
     * 订阅者列表：session -> 订阅参数。
     */
    protected val subscribers = ConcurrentHashMap<DefaultWebSocketServerSession, P>()

    /**
     * 上一次推送的数据指纹（用于判断内容是否变化）。
     */
    @Volatile
    protected var lastFingerprint: String? = null

    /**
     * 版本计数器，每推送一次递增。
     */
    @Volatile
    protected var version: Long = 0L

    /**
     * 读取原始数据。
     * 子类从自有数据源读取（本地服务、本地状态、本地计算等）。
     */
    protected abstract fun readData(): D?

    /**
     * 按订阅参数组装 WebSocket Payload。
     */
    protected abstract fun assemblePayload(rawData: D, params: P): String

    /**
     * 计算数据指纹，用于判断内容是否发生变化。
     * 默认实现为 toString 哈希，子类可覆盖为更高效的实现。
     */
    protected open fun computeFingerprint(rawData: D): String {
        return rawData.toString().hashCode().toString()
    }

    /**
     * 执行一次数据同步：读取 → 比对 → 推送。
     * 非 K 线 Provider 自行决定何时调用（事件驱动 / 定时 / 状态变化）。
     */
    protected fun doSync(timestamp: Long) {
        if (subscribers.isEmpty()) return

        val rawData = readData()
        if (rawData == null) return

        val fingerprint = computeFingerprint(rawData)
        if (fingerprint == lastFingerprint) return

        val action = if (lastFingerprint == null) WsAction.SYNC else WsAction.UPDATE
        subscribers.forEach { (session, params) ->
            val payload = assemblePayload(rawData, params)
            pushToSession(session, payload, action, timestamp)
        }

        lastFingerprint = fingerprint
        version++
    }

    /**
     * 添加订阅者。
     */
    open fun subscribe(session: DefaultWebSocketServerSession, params: P) {
        val shouldImmediatePush = subscribers.isEmpty()
        subscribers[session] = params
        if (shouldImmediatePush) {
            doSync(System.currentTimeMillis())
        }
    }

    /**
     * 移除订阅者。
     */
    fun unsubscribe(session: DefaultWebSocketServerSession) {
        subscribers.remove(session)
    }

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()
    fun subscriberCount(): Int = subscribers.size

    protected fun pushToSession(
        session: DefaultWebSocketServerSession,
        payload: String,
        action: WsAction,
        timestamp: Long
    ) {
        val event = WsEvent(
            topic = topic,
            action = action,
            payload = payload,
            timestamp = timestamp
        )
        AppWebSocketConnectionManager.sendToSession(session, event)
    }
}
```

### 2.2 KlineDataProvider 抽象基类（K 线）

```kotlin
/**
 * K 线 Provider 抽象基类。
 *
 * 一个 K 线 Topic 对应一个 Provider 实例。
 * 内部按 (tsCode, period) 分组管理订阅状态和数据指纹。
 * 不继承 DataProvider，订阅模型完全不同。
 */
abstract class KlineDataProvider<P : Any>(
    val topic: WsTopic,
    protected val snapshot: SnapshotManager
) {
    /**
     * 按 key 分组的订阅者集合。
     */
    protected val subscribers =
        ConcurrentHashMap<CandleKey, MutableSet<DefaultWebSocketServerSession>>()

    /**
     * 按 key 分组的上次数据指纹。
     */
    protected val fingerprints =
        ConcurrentHashMap<CandleKey, String>()

    /**
     * session -> key 的反向索引，用于快速取消订阅。
     */
    protected val sessionKeys =
        ConcurrentHashMap<DefaultWebSocketServerSession, MutableSet<CandleKey>>()

    /**
     * session -> 订阅参数。
     * 同一 session 可能对多个 key 使用相同参数，这里只存一份。
     */
    protected val sessionParams =
        ConcurrentHashMap<DefaultWebSocketServerSession, P>()

    /**
     * SyncLooper 广播的 DATA_SYNC 信号入口。
     * K 线 Provider 唯一的刷新触发点。
     */
    fun onDataSync(changedKeys: Collection<CandleKey>) {
        if (changedKeys.isEmpty()) return

        changedKeys.forEach { key ->
            val sessions = subscribers[key] ?: return@forEach
            if (sessions.isEmpty()) return@forEach

            val rawData = snapshot.readCandles(key.tsCode, key.period)
            if (rawData.isNullOrEmpty()) return@forEach

            val fingerprint = computeFingerprint(key, rawData)
            val lastFp = fingerprints[key]
            if (fingerprint == lastFp) return@forEach

            val action = if (lastFp == null) WsAction.SYNC else WsAction.UPDATE
            sessions.forEach { session ->
                val params = sessionParams[session] ?: return@forEach
                val payload = assemblePayload(key, rawData, params)
                pushToSession(session, payload, action, System.currentTimeMillis())
            }

            fingerprints[key] = fingerprint
        }
    }

    /**
     * 订阅指定 (tsCode, period) 的 K 线数据。
     */
    fun subscribe(
        session: DefaultWebSocketServerSession,
        key: CandleKey,
        params: P
    ) {
        sessionParams[session] = params
        subscribers.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(session)
        sessionKeys.computeIfAbsent(session) { ConcurrentHashMap.newKeySet() }.add(key)

        // 立即尝试推送一次（如果 snapshot 已有数据）
        if (snapshot.hasCandles(key.tsCode, key.period)) {
            onDataSync(listOf(key))
        }
    }

    /**
     * 取消指定 session 的所有 K 线订阅。
     */
    fun unsubscribe(session: DefaultWebSocketServerSession) {
        sessionParams.remove(session)
        val keys = sessionKeys.remove(session)
        keys?.forEach { key ->
            subscribers[key]?.remove(session)
        }
    }

    fun hasSubscribers(): Boolean = subscribers.values.any { it.isNotEmpty() }
    fun subscriberCount(): Int = sessionParams.size

    protected abstract fun computeFingerprint(key: CandleKey, rawData: List<Candle>): String

    protected abstract fun assemblePayload(
        key: CandleKey,
        rawData: List<Candle>,
        params: P
    ): String

    protected fun pushToSession(
        session: DefaultWebSocketServerSession,
        payload: String,
        action: WsAction,
        timestamp: Long
    ) {
        val event = WsEvent(
            topic = topic,
            action = action,
            payload = payload,
            timestamp = timestamp
        )
        AppWebSocketConnectionManager.sendToSession(session, event)
    }
}
```

### 2.3 Provider 注册表

```kotlin
class ProviderRegistry {
    // topic -> DataProvider 或 KlineDataProvider
    private val providers = ConcurrentHashMap<WsTopic, Any>()

    fun register(provider: DataProvider<*, *>) {
        providers[provider.topic] = provider
    }

    fun registerKline(provider: KlineDataProvider<*>) {
        providers[provider.topic] = provider
    }

    @Suppress("UNCHECKED_CAST")
    fun <P : Any, D : Any> find(topic: WsTopic): DataProvider<P, D>? {
        return providers[topic] as? DataProvider<P, D>
    }

    fun getKlineProvider(topic: WsTopic = WsTopic.CANDLE_DATA): KlineDataProvider<*>? {
        return providers[topic] as? KlineDataProvider<*>
    }
}
```

### 2.4 动态 DataProvider 快照契约

除 K 线 Topic 级 Provider 外，当前还有一类按业务 key 动态激活的 Provider。
它们通过统一 `DataProvider<K, T>` 契约暴露当前快照与生命周期：

```kotlin
interface DataProvider<K : DataProviderKey, T> {
    val key: K

    val snapshotFlow: StateFlow<DataProviderSnapshot<T>?>

    fun currentSnapshot(): DataProviderSnapshot<T>? = snapshotFlow.value

    suspend fun onPhaseChanged(phase: ExecutionPhase, cause: UpdateCause)
    suspend fun refreshHistorical(cause: UpdateCause)
    suspend fun refreshRealtime(cause: UpdateCause)
    suspend fun recalibrate(cause: UpdateCause)

    suspend fun release()
}
```

`AbstractDataProvider` 负责收敛这类 Provider 的快照发布规则：

```kotlin
abstract class AbstractDataProvider<K : DataProviderKey, T>(
    final override val key: K,
    private val snapshotStore: SnapshotStore<T>
) : DataProvider<K, T> {
    private val mutableSnapshotFlow = MutableStateFlow(snapshotStore.get(key.id))

    final override val snapshotFlow: StateFlow<DataProviderSnapshot<T>?> =
        mutableSnapshotFlow

    protected suspend fun publishSnapshot(snapshot: DataProviderSnapshot<T>) {
        snapshotStore.put(snapshot)
        mutableSnapshotFlow.value = snapshot
    }

    final override suspend fun release() {
        mutableSnapshotFlow.value = null
        snapshotStore.remove(key.id)
    }
}
```

`SnapshotStore` 在这条链路中只负责当前值缓存：

```kotlin
interface SnapshotStore<T> {
    fun get(keyId: String): DataProviderSnapshot<T>?
    suspend fun put(snapshot: DataProviderSnapshot<T>)
    suspend fun remove(keyId: String)
}
```

## 3. 具体 Provider 实现

### 3.1 CandleDataProvider（K 线数据）

**Topic**：`CANDLE_DATA`

**数据来源**：snapshot 周期分区

**驱动方式**：SyncLooper DATA_SYNC

```kotlin
class CandleDataProvider(
    snapshot: SnapshotManager
) : KlineDataProvider<CandleSubscribeRequest>(
    topic = WsTopic.CANDLE_DATA,
    snapshot = snapshot
) {
    override fun computeFingerprint(key: CandleKey, rawData: List<Candle>): String {
        val last = rawData.lastOrNull() ?: return "empty"
        return "${last.date}:${last.open}:${last.high}:${last.low}:${last.close}:${last.volume}"
    }

    override fun assemblePayload(
        key: CandleKey,
        rawData: List<Candle>,
        params: CandleSubscribeRequest
    ): String {
        // 日期范围过滤
        val filtered = rawData.filter { candle ->
            val candleDate = candle.tradeTime?.substringBefore(" ") ?: candle.date.toString()
            (params.startDate == null || candleDate >= params.startDate) &&
            (params.endDate == null || candleDate <= params.endDate)
        }

        // limit 尾部裁剪
        val limited = params.limit?.let { filtered.takeLast(it.coerceAtLeast(1)) } ?: filtered

        // 转换为前端格式
        val candleDataList = limited.mapIndexed { index, candle ->
            candle.toSocketCandleData(
                useAdjusted = params.useAdjusted,
                previous = limited.getOrNull(index - 1)
            )
        }

        val payload = CandleDataPayload(
            tsCode = key.tsCode,
            candles = candleDataList,
            totalCount = candleDataList.size,
            requestParams = params
        )
        return Json.encodeToString(payload)
    }
}
```

### 3.2 MarketStatusDataProvider（市场状态）

**Topic**：`MARKET_STATUS`

**数据来源**：本地计算（ExecutionPhaseService 当前状态）

**驱动方式**：监听阶段变化，变化时主动推送

```kotlin
class MarketStatusDataProvider(
    private val executionPhaseService: ExecutionPhaseService,
    scope: CoroutineScope
) : DataProvider<Unit, MarketStatusSnapshot>(
    topic = WsTopic.MARKET_STATUS,
    scope = scope
) {
    /**
     * 自带缓存：上次读取的市场状态。
     */
    @Volatile
    private var cachedStatus: MarketStatusSnapshot? = null

    init {
        executionPhaseService.phaseFlow
            .onEach { phase ->
                cachedStatus = MarketStatusSnapshot(
                    phase = phase,
                    tradeDate = resolveEffectiveTradeDate(),
                    updatedAt = System.currentTimeMillis()
                )
                doSync(System.currentTimeMillis())
            }
            .launchIn(scope)
    }

    override fun readData(): MarketStatusSnapshot? {
        return cachedStatus
    }

    override fun assemblePayload(rawData: MarketStatusSnapshot, params: Unit): String {
        val payload = MarketStatusPayload(
            phase = rawData.phase.name,
            isTrading = rawData.phase == ExecutionPhase.TRADING_ACTIVE,
            tradeDate = rawData.tradeDate.toString(),
            serverTime = System.currentTimeMillis()
        )
        return Json.encodeToString(payload)
    }
}
```

### 3.3 SentimentDataProvider（市场情绪）

**Topic**：`SENTIMENT`

**数据来源**：本地计算服务

**驱动方式**：自行定时计算，结果变化时主动推送

```kotlin
class SentimentDataProvider(
    private val sentimentCalculator: SentimentCalculator,
    scope: CoroutineScope
) : DataProvider<Unit, SentimentSnapshot>(
    topic = WsTopic.SENTIMENT,
    scope = scope
) {
    /**
     * 自带缓存：上次计算的情绪快照。
     */
    @Volatile
    private var cachedSentiment: SentimentSnapshot? = null

    init {
        scope.launch {
            while (isActive) {
                val newSentiment = sentimentCalculator.calculate()
                if (newSentiment != null && newSentiment != cachedSentiment) {
                    cachedSentiment = newSentiment
                    doSync(System.currentTimeMillis())
                }
                delay(30_000L)
            }
        }
    }

    override fun readData(): SentimentSnapshot? {
        return cachedSentiment
    }

    override fun assemblePayload(rawData: SentimentSnapshot, params: Unit): String {
        val payload = SentimentPayload(
            marketSentiment = rawData.marketSentiment,
            sectorSentiments = rawData.sectorSentiments,
            updatedAt = rawData.updatedAt
        )
        return Json.encodeToString(payload)
    }
}
```

### 3.4 IntradayFactorDataProvider（盘中因子）— 已迁移至 strategy-server/service

> 原 Ktor 端 `IntradayFactorProvider` 已物理删除（PR2 单 owner 切换）。盘中因子投影 owner 现在是 `strategy-server/service/.../runtime/IntradayStrategyRuntime.kt`，作为 SERVICE_OWNED_TOPICS 的唯一 owner，主动产出 `INTRADAY` snapshot。Ktor 不再持有任何盘中因子 Provider 与本地 fallback。

**Topic**：`INTRADAY`（service-owned）

**数据来源**：T-1 因子滚动状态 + 权威 DAY realtime 事实 + 复权基准（全部由 service 进程内读取）

盘中 R 轨固定流程（owner 在 service 进程内，非 Ktor）：

```
DailyStockFactorRepository (T 之前最近一次落库)
  + DailyFactorRollingStateRepository (T-1 滚动状态)
  + service 内部 KtorSnapshotStrategyRealtimeDailyFactSource (本地 strategy socket 读取 Ktor DAY snapshot)
  + DefaultStrategyBarRepository.getFirstAdjMap (复权基准)
  → PreparedBarFactory.fromCandle(...)
  → StockFactorCalculator.calculate(state, realtimeBar)
  → IntradayPortfolioGenerator → INTRADAY snapshot
```

H/R/merged 语义：
- H 轨：最近一个已落库的日频因子快照
- R 轨：完整 rolling state + 标准化实时 `PreparedBar` 递推出的当日因子
- merged 轨：以 H 为底，对成功生成 R 的股票逐只覆盖

缺少 T-1 rolling state、firstAdj、当日 adj 或实时 candle 时，不生成该股票 R，继续保留 H。这个降级规则是为了避免盘中产生一套与盘后不可追平的错误口径。Ktor 端三个前端订阅（`INTRADAY_SNAPSHOT` / `STRATEGY_POSITIONS` / `STRATEGY_POSITION_TRACKING`）只通过 `SocketStrategyRuntimeClient` 消费 service snapshot，service 不可达时直接返回 `WsAction.ERROR`。

### 3.5 StrategyPositionDataProvider（策略持仓）

**Topic**：`STRATEGY_POSITION`

**数据来源**：本地状态（StrategyPositionHolder）

**驱动方式**：监听本地状态变化，变化时主动推送

```kotlin
class StrategyPositionDataProvider(
    private val positionHolder: StrategyPositionHolder,
    scope: CoroutineScope
) : DataProvider<Unit, StrategyPositionSnapshot>(
    topic = WsTopic.STRATEGY_POSITION,
    scope = scope
) {
    init {
        positionHolder.snapshotFlow
            .onEach { snapshot ->
                if (snapshot != null) {
                    doSync(System.currentTimeMillis())
                }
            }
            .launchIn(scope)
    }

    override fun readData(): StrategyPositionSnapshot? {
        return positionHolder.snapshotFlow.value
    }

    override fun assemblePayload(rawData: StrategyPositionSnapshot, params: Unit): String {
        val payload = StrategyPositionPayload(
            currentPositions = rawData.currentPositions,
            nextSessionSelections = rawData.nextSessionSelections,
            tradeDate = rawData.tradeDate.toString()
        )
        return Json.encodeToString(payload)
    }
}
```

## 4. Topic 与 Provider 映射表

| Topic | Provider 类 | 数据来源 | 驱动方式 | 缓存位置 |
|-------|------------|----------|----------|----------|
| `CANDLE_DATA` | `CandleDataProvider` | snapshot K 线分区 | SyncLooper DATA_SYNC | snapshot |
| `MARKET_STATUS` | `MarketStatusDataProvider` | 本地阶段服务 | 阶段变化事件 | Provider 内部 |
| `INTRADAY_FACTOR` | strategy-service `INTRADAY` snapshot 经 Ktor 透传 | rolling state + 权威 realtime DAY + 复权基准（service 端） | service 主动推送 + Ktor `SocketStrategyRuntimeClient` 订阅 | strategy-service 内部 hub |
| `SENTIMENT` | `SentimentDataProvider` | 本地计算服务 | 自行定时（30s） | Provider 内部 |
| `STRATEGY_POSITION` | `StrategyPositionDataProvider` | 本地状态 Holder | 状态变化事件 | Provider 内部 |

新增 Topic 时：
1. 判断是否属于 K 线数据：是则继承 `KlineDataProvider`，否则继承 `DataProvider`
2. 确定数据来源：snapshot（K 线）/ 本地服务 / 本地状态
3. 确定驱动方式：SyncLooper DATA_SYNC / 事件监听 / 自行定时
4. 确定缓存位置：snapshot（K 线）/ Provider 内部（非 K 线）
5. 在 `WsTopic` 枚举中注册 Topic

## 5. 推送语义

| 动作 | 触发时机 | 数据内容 | 说明 |
|------|----------|----------|------|
| **SYNC** | 客户端首次订阅且数据就绪 | 全量裁剪后的数据 | 让客户端立即渲染 |
| **UPDATE** | 数据发生变化 | 全量裁剪后的数据 | 前端应全量替换 |
| **无推送** | 数据未变化 | — | 避免无效推送 |
| **无推送** | 数据未就绪（如 snapshot 未命中） | — | 保持静默，等待下次同步 |

> **K 线首次订阅延迟说明**：
> - 日区：启动时预加载历史，通常首次订阅可立即收到数据
> - x 分区历史：首次请求时 snapshot 未命中，需向 apiLayer 提交异步任务。典型延迟为 **1-2 个 SYNC 周期**（1-2 秒），这是预期行为
> - x 分区实时：LRU 未命中时同样需异步加载，延迟同历史

## 6. K 线 Provider 数据流

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         客户端订阅 K 线                                  │
│                                                                         │
│   ① 客户端发送 SUBSCRIBE(topic=CANDLE_DATA, tsCode=A, period=MIN_5)    │
│                        │                                                │
│                        ▼                                                │
│   ② CandleDataProvider.subscribe(session, key=A:MIN_5, params)         │
│      记录订阅状态，若 snapshot 有数据立即推送 SYNC                        │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      K 线 DATA_SYNC 驱动流程（每秒）                      │
│                                                                         │
│   ① SyncLooper drain snapshot 版本变更 key                              │
│                        │                                                │
│                        ▼                                                │
│   ② CandleDataProvider.onDataSync(changedKeys)                          │
│      只处理仍有订阅者的变更 key                                           │
│                        │                                                │
│                        ▼                                                │
│   ③ 对每个 key 调用 snapshot.readCandles(tsCode, period)                │
│      - 日区：dayCache.getMerged(tsCode)                                 │
│      - x分区：history + realtime 拼接                                   │
│      - 周/月：lruCache.get(tsCode)                                      │
│                        │                                                │
│                        ▼                                                │
│   ④ 若返回 null（snapshot 未命中）                                       │
│      → snapshot 内部向 apiLayer 提交异步任务（本次不推送）               │
│      → 等待 apiLayer 回调写入缓存后，下次 DATA_SYNC 命中                 │
│                        │                                                │
│                        ▼                                                │
│   ⑤ 若返回数据，比对指纹                                                 │
│      - 未变化 → 跳过该 key                                               │
│      - 变化 → 按每个订阅者参数裁剪并推送                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

## 7. 关键约束

### 7.1 K 线 Provider 不直接调用 apiLayer

```
错误：CandleDataProvider 发现 snapshot 返回 null → 直接调用 tushare.getRtMinDaily()
正确：CandleDataProvider 发现 snapshot 返回 null → 保持静默，由 snapshot 内部向 apiLayer 下发任务
```

K 线 Provider 与 apiLayer 之间必须通过 snapshot 层解耦。

### 7.2 Provider 按 Topic 粗分

```
错误：每只股票每个周期创建一个 CandleDataProvider（导致 40000+ 实例）
正确：CANDLE_DATA Topic 只有一个 CandleDataProvider 实例，内部按 key 分组管理订阅
```

Provider 实例数 = Topic 数（5-10 个），不是股票数 × 周期数。

### 7.3 非 K 线 Provider 自带数据源

```
错误：MarketStatusDataProvider 从 snapshot 读取（snapshot 不缓存市场状态）
正确：MarketStatusDataProvider 直接从 ExecutionPhaseService 读取，自行缓存
```

非 K 线数据不经过 snapshot，各 Provider 自行解决数据来源和缓存。

### 7.4 K 线 Provider 只响应 SyncLooper

```
错误：CandleDataProvider 内部启动 timer，每秒自行刷新
正确：CandleDataProvider 只响应 SyncLooper 的 DATA_SYNC 信号
```

所有 K 线 Provider 的刷新节奏由 SyncLooper 统一控制。

### 7.5 K 线 Provider 与非 K 线 Provider 独立继承

```
错误：KlineDataProvider 继承 DataProvider，导致 lastFingerprint / doSync 等成员成为死代码
正确：KlineDataProvider 与 DataProvider 是两个独立基类，各自拥有精简接口
```

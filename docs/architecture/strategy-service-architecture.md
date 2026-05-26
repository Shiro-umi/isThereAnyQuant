# Strategy Service 架构规划

本文档定义 strategy 模块从当前 Ktor 内嵌运行时演进为独立 strategy service 的目标架构、模块落点、通信协议边界和分阶段迁移路径。

如果是后续 agent 接手继续实现，先读取 `docs/architecture/strategy-service-agent-handoff.md`，再回到本文档确认完整架构背景。

当前落地状态:

- 已创建 `strategy-server/` 聚合目录，并纳入 Gradle: `:strategy-server:contract`、`:strategy-server:core`、`:strategy-server:client`、`:strategy-server:service`、`:strategy-server:testing`。
- 已将纯策略内核从 `database` 迁入 `:strategy-server:core`，包名收束为 `org.shiroumi.strategy.core.*`。
- 盘后 700 日历史窗口 + 21 步算法链已迁入 `:strategy-server:service:postmarket`（`PostMarketOrchestrator` / `PostMarketPreparationJob` / `PostMarketRebuildPolicy`），`PostMarketStrategyRuntime` 直接调用 service 内部 owner，不再委托 `database.StrategyPreparationFacade`。
- `database` 当前只保留策略表、Repository、schema/bootstrap 和 seed 映射等持久化职责；旧的 `StrategyPreparationFacade` / `DailyStrategyDataPreparationJob` 已物理删除，盘后业务全部由 `:strategy-server:service:postmarket` 持有。
- `:strategy-server:service` 是可独立运行的 socket 进程，监听 JSONL snapshot/command/subscribe 连接，维护 service 侧内存快照，订阅连接回放 current snapshot、广播后续 snapshot、发布 `HEALTH` snapshot；同时是 `INTRADAY` / `POSITIONS` / `POSITION_TRACKING` 的唯一 owner，外部 socket 写入这些 owned topic 会被忽略。
- `strategy-service` 已接入盘中 runtime: 只读主板 universe、`sentiment_runtime_seed`、`daily_market_sentiment`、`daily_stock_factor`、`daily_factor_rolling_state` 与当前/最近目标组合，并通过本地 strategy socket 从 Ktor `CandleSnapshotManager` DAY snapshot 读取 realtime fact，写入 service 短 TTL fact cache；再使用 `PreparedBarFactory` 统一准备情绪/因子实时 bar，调用 `MarketSentimentCalculator.calculateStrict`、`StockFactorCalculator.calculate(state, bar)` 和 `IntradayPortfolioGenerator` 主动生产 `INTRADAY` / `POSITIONS` snapshot。该 runtime 不写 `daily_*` 确认结果表。
- `ktor-server` 是 service 的纯 adapter: 通过 `:strategy-server:client` 提供的 `SocketStrategyRuntimeClient` 消费 `INTRADAY` / `POSITIONS` / `POSITION_TRACKING` / `HEALTH`；`INTRADAY_SNAPSHOT` / `STRATEGY_POSITIONS` / `STRATEGY_POSITION_TRACKING` 三个前端订阅都 service-only，service 不可达时返回 `WsAction.ERROR`，不再有任何本地 Provider/Projection/facade fallback 路径。
- Ktor 端 `StrategyRuntimeBridge` 不再持有任何 SERVICE_OWNED_TOPICS 的 publish 路径或 `LocalStrategySnapshotHub` 字段；本地 `StrategyPositionHolder` 只保留冷启动 last-known 加载（`initialize()`）与 service snapshot 同步写入路径（`updateFromService`）。
- `RefreshIntraday` command 已驱动 service 侧盘中 runtime 刷新并发布 snapshot；`RebuildDate` / `RebuildRange` 已接入 service 侧盘后 runtime（`PostMarketOrchestrator`）直接写确认结果并发布 `POSITIONS` / `POSITION_TRACKING`。Ktor 盘后 `HistoricalDataUpdateOrchestrator` 只通过 `StrategyCommand.RebuildDate` 驱动 service；service 不可达时入补偿队列指数退避重试，仍只通过 service 重做。

目标不是把现有代码按目录机械搬迁，而是围绕真实业务链路收束职责:

```
盘后确认:
日线事实 -> 策略因子/情绪/目标组合/审计 -> 确认结果落库 -> Ktor/API/前端读取

盘中投影:
盘后 seed + 实时 DAY facts -> strategy runtime -> snapshot stream -> Ktor -> 前端 WS

Agent 迭代:
Agent 后台修改/验证/部署 strategy service -> strategy service 独立重启 -> Ktor 不重启
```

## 1. 架构结论

采用独立的 Strategy Service 架构，而不是泛化的 data-layer 大抽象。

推荐目标形态:

```
strategy-server/
  contract/   # Ktor 与 strategy-service 的稳定内部协议
  core/       # 纯策略业务核心: 情绪、因子、组合选择、持仓推演
  service/    # 独立进程: provider/runtime/snapshot publisher/database writer
  client/     # Ktor 侧内部客户端: local/remote snapshot + command gateway
  testing/    # 策略回放、contract fake、agent 验证工具

ktor-server/
  strategy adapter only:
    - external REST / frontend WS
    - internal StrategyRuntimeClient wiring
    - auth/session/frontend topic adaptation

database/
  persistence implementation:
    - strategy confirmed tables
    - historical facts
    - repository implementation

shared/
  frontend transport DTO only
```

Gradle 物理落点采用一个大的 `strategy-server` 聚合目录，而不是多个顶层 `strategy-*` 模块平铺:

```
strategy-server/
  settings: logical subprojects
  contract/build.gradle.kts
  core/build.gradle.kts
  service/build.gradle.kts
  client/build.gradle.kts
  testing/build.gradle.kts
```

对应 Gradle project path:

```
:strategy-server:contract
:strategy-server:core
:strategy-server:service
:strategy-server:client
:strategy-server:testing
```

这样既满足 "strategy-*" 整体归在一个大 module 下面，又避免顶层模块膨胀。

## 2. 为什么不是泛化 data-layer

`provider`、`snapshot`、`datasource`、`api` 在 strategy 服务化后不是同一种层级:

| 概念 | 正确归属 | 说明 |
| --- | --- | --- |
| provider | `:strategy-server:service` 内部 | 负责生产 H/R/merged 或 runtime 输入，不是 Ktor 外部协议 |
| snapshot | `:strategy-server:contract` 抽象 + service/client 实现 | 跨进程状态同步协议，底层可以本地内存或 socket |
| datasource | `:strategy-server:core` 端口 + `database`/`service` 实现 | 历史事实、确认结果、实时行情读取能力 |
| api | `ktor-server` 外部适配 | 面向前端/外部调用，不是 strategy 内核 |

因此不应设计一个大而泛的 `data-layer` 来同时承载这些概念。更稳妥的边界是:

```
strategy-core:     业务算法
strategy-contract: 内部进程间协议
strategy-service:  算法运行时 + snapshot 发布者 + 确认结果 owner
ktor-server:       外部 API/WS adapter
```

## 3. 模块职责

### 3.1 `:strategy-server:contract`

职责:

- 定义 Ktor 与 strategy service 的内部稳定协议。
- 定义 snapshot envelope、topic、version、health、command、ack/error。
- 定义 `SnapshotSource` / `SnapshotSink` / `StrategyRuntimeClient` 等端口。
- 不依赖 Ktor、Exposed、具体 WebSocket 实现。

核心模型建议:

```kotlin
data class StrategySnapshotEnvelope<T>(
    val topic: StrategyTopic,
    val version: Long,
    val sourceInstanceId: String,
    val publishedAt: Long,
    val payload: T
)

enum class StrategyTopic {
    INTRADAY,
    POSITIONS,
    POSITION_TRACKING,
    HEALTH
}

interface StrategySnapshotSource<T> {
    suspend fun current(topic: StrategyTopic): StrategySnapshotEnvelope<T>?
    fun observe(topic: StrategyTopic): kotlinx.coroutines.flow.Flow<StrategySnapshotEnvelope<T>>
}

interface StrategyCommandClient {
    suspend fun send(command: StrategyCommand): StrategyCommandAck
}
```

协议约束:

- `SYNC` 和 `UPDATE` 都必须携带完整可消费视图。
- snapshot version 只在业务 payload 变化时递增。
- 每个 snapshot 都必须带 `sourceInstanceId`，用于 Ktor 识别 strategy service 重启后的新来源。
- 内部 socket frame、snapshot 和 command ack 必须携带 `contractVersion`。Ktor 只接受兼容 contract version 的业务 snapshot；不兼容时保留 last-known snapshot，并通过 `HEALTH` topic 暴露 `CONTRACT_VERSION_MISMATCH`。
- 传输 DTO 不直接暴露内部 domain model。

### 3.2 `:strategy-server:core`

职责:

- 纯策略业务核心。
- 包含因子、情绪、组合选择、盘中推演、持仓差异计算。
- 不依赖 Ktor、WebSocket、Exposed transaction。

应该迁入或抽象出来的能力:

- `StockFactorCalculator`
- `MarketSentimentCalculator`
- `PortfolioSelectionEngine`
- `TargetPortfolioGenerator`
- `IntradayFactorCalculator`
- `IntradaySentimentCalculator`
- `IntradayPortfolioGenerator`
- `PreparedBarFactory` 及价格口径模型

已落地:

- `strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/daily`
- `strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/daily/preprocessing`
- `strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/intraday`
- `strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/sentiment`

迁移约束:

- core 内不允许依赖 Exposed Repository 或 Ktor。
- 历史窗口、seed、实时 candle 等事实必须由 service/provider adapter 传入 core。
- `IntradaySentimentCalculator` 不再从 Repository 自行读取历史情绪；缺省历史 seed 时只回退 base sentiment，避免污染 core 边界。

核心约束:

- `rankScore` 只表示单股因子分。
- `selectionScore` 表示组合横截面最终分。
- 盘后和盘中必须复用同一套组合选择引擎。
- 盘中 projection 不写确认结果表。

### 3.3 `:strategy-server:service`

职责:

- 独立进程，最终由 Agent 可独立迭代、构建、重启。
- 拥有 strategy runtime 生命周期。
- 订阅/读取实时 DAY facts。
- 执行盘中情绪、盘中选股、持仓推演。
- 执行盘后确认策略计算，并写入确认结果表。
- 发布内部 snapshot stream 给 Ktor。

实际目录结构（service 内部已落地，不要再以"provider/"命名 service 内部组件）:

```
strategy-server/service/src/main/kotlin/org/shiroumi/strategy/service/...
  StrategyServiceMain.kt              # service 进程入口 + SERVICE_OWNED_TOPICS 单 owner 守卫
                                      # + 命令分发 (HealthCheck / RefreshIntraday / RebuildDate / RebuildRange)

  runtime/
    StrategyRealtimeDailyFactSource.kt        # Ktor DAY snapshot socket 读取 + 短 TTL cache
    IntradayStrategyRuntime.kt                # 盘中 H/R/merged 唯一 owner（含 DefaultIntradayStrategyRuntimeDataSource）
    PostMarketStrategyRuntime.kt              # 盘后 runtime 入口（委托 postmarket/ owner，含 DefaultPostMarketStrategyRuntimeDataSource）
    StrategyPositionTrackingRuntime.kt        # 持仓追踪 runtime（POSITION_TRACKING owner，含 DefaultStrategyPositionTrackingDataSource）

  postmarket/
    PostMarketOrchestrator.kt                 # 盘后业务编排 owner
    PostMarketPreparationJob.kt               # 盘后 21 步算法链 + 700 日窗口（直接通过 Daily*Repository 写确认结果）
    PostMarketRebuildPolicy.kt                # rebuild 窗口/并发/重试策略

  preprocessing/
    ...                                       # 盘后预处理（PreparedBar 构造 / 价格口径管线）

  universe/
    ...                                       # 主板 universe 过滤
```

> Snapshot hub 位于 `:strategy-server:client`（`LocalStrategySnapshotHub`），由 service 进程内部使用 + socket 广播给 ktor，并未在 service 模块内独立目录。
> 确认结果写入直接通过 `:database` 的 `Daily*Repository` 完成（`DailyStockFactorRepository` / `DailyMarketSentimentRepository` / `DailyTargetPortfolioRepository` / `DailyStrategyAuditRepository` / `SentimentRuntimeSeedRepository`），未提取独立 writer。

最终 owner 规则:

- `strategy-service` 是 `daily_stock_factor`、`daily_market_sentiment`、`sentiment_runtime_seed`、`daily_target_portfolio`、`daily_strategy_audit` 的业务 owner。
- `ktor-server` 可以读这些确认结果，但不生成、不修正、不旁路写入这些确认结果。

### 3.4 `:strategy-server:client`

职责:

- 给 `ktor-server` 使用的内部 adapter。
- 屏蔽 strategy runtime 是本地内存还是远程 socket。
- 提供统一的 `StrategyRuntimeClient`。

实现建议:

```
LocalStrategyRuntimeClient
  - 过渡期直接调用本进程 runtime/snapshot hub

SocketStrategyRuntimeClient
  - 生产期连接 strategy-service
  - 断线时保留 last-known snapshot
  - 重连后按 version/sourceInstanceId 切换
```

已落地:

- `strategy-server/client/src/main/kotlin/org/shiroumi/strategy/client/LocalStrategySnapshotHub.kt`
- `strategy-server/client/src/main/kotlin/org/shiroumi/strategy/client/SocketStrategySnapshotPublisher.kt`
- `strategy-server/client/src/main/kotlin/org/shiroumi/strategy/client/SocketStrategyRuntimeClient.kt`
- `ktor-server/src/main/kotlin/org/shiroumi/server/runtime/strategy/StrategyRuntimeBridge.kt`

Ktor 已切换为单 owner adapter 模式: `StrategyRuntimeBridge` 默认启用 `SocketStrategyRuntimeClient`，除非显式设置 `STRATEGY_SOCKET_CONSUME_ENABLED=false`；`INTRADAY_SNAPSHOT` / `STRATEGY_POSITIONS` / `STRATEGY_POSITION_TRACKING` 三个前端订阅都只读 service current/observe，service 暂无首包时等待 remote flow，service 不可达时返回 `WsAction.ERROR`。`STRATEGY_POSITIONS` remote payload 通过 `updateFromService` 写入 Ktor `StrategyPositionHolder`，作为 last-known cache。Ktor 已不再持有任何 Provider 激活、warmup、本地 fallback Projection 或全市场重算路径。

Ktor 侧只依赖:

```kotlin
interface StrategyRuntimeClient {
    suspend fun currentIntraday(): StrategyIntradaySnapshot?
    fun observeIntraday(): Flow<StrategyIntradaySnapshot>

    suspend fun currentPositions(): StrategyPositionSnapshot?
    fun observePositions(): Flow<StrategyPositionSnapshot>

    suspend fun health(): StrategyHealthSnapshot
    suspend fun send(command: StrategyCommand): StrategyCommandAck
}
```

### 3.5 `:strategy-server:testing`

职责:

- Agent 后台迭代 strategy 时使用的验证工具。
- 提供历史回放、fixture、fake snapshot source、contract compatibility test。
- 不进入 Ktor 运行时主链路。

建议能力:

- 单日盘后计算回放。
- 盘中 DAY fact replay。
- snapshot contract golden test。
- `selectionScore` 排序一致性测试。
- service restart compatibility test。

## 4. Snapshot 高级抽象

目标语义:

```
Ktor 不关心 snapshot 来自本地内存还是另一个进程。
Strategy service 不关心 Ktor 如何再转发给前端。
Snapshot 是完整业务视图，不是增量 patch。
```

建议抽象:

```kotlin
interface SnapshotSource<K, T> {
    suspend fun current(key: K): T?
    fun observe(key: K): Flow<T>
}

interface SnapshotSink<K, T> {
    suspend fun publish(key: K, value: T)
}
```

实现:

| 实现 | 用途 |
| --- | --- |
| `LocalStrategySnapshotHub` | service 内部 hub，service 内 runtime owner 写入、socket publisher 读出 |
| `SocketStrategySnapshotPublisher` | service 端：把 service 内部 hub 中的 owned snapshot（INTRADAY / POSITIONS / POSITION_TRACKING / HEALTH）单向广播给所有连接的 Ktor socket client |
| `SocketStrategyRuntimeClient` | Ktor 端：唯一 snapshot 入口，订阅 service current/observe，service 不可达时返回 `WsAction.ERROR`，无任何本地兜底 |

关键约束:

- snapshot 层不做选股计算。
- snapshot 层不查确认结果表。
- snapshot 层不直接管理前端 session。
- snapshot payload 必须是完整可消费视图。
- 断线重连时，Ktor 可以继续推送 last-known snapshot，并由 `SocketStrategyRuntimeClient` 在本地 `HEALTH` topic 标记 `DISCONNECTED`，重连后标记 `CONNECTED`。

## 5. 内部 socket 协议

内部 socket 是 Ktor 和 strategy-service 之间的服务间通信协议，不复用前端 `/ws/app-stream` 的业务语义。

当前已落地协议:

- 传输: 本机 TCP socket，默认 `127.0.0.1:9971`，每行一条 JSON frame。
- Ktor 消费开关: 默认订阅 service snapshot；仅当 `STRATEGY_SOCKET_CONSUME_ENABLED=false` 时禁用，作为 service -> Ktor 的 last-known 接入边界。
- Ktor 单 owner 约束: `STRATEGY_KTOR_*_FALLBACK_*` 系列环境变量（盘中、策略持仓、跟踪、盘后、反向 publish）已在物理删除链路时一并移除，Ktor 不再产生任何 SERVICE_OWNED_TOPICS 的 snapshot，service 不可达时订阅返回 `WsAction.ERROR`。
- Service 启动: `:strategy-server:service` 监听 `STRATEGY_SOCKET_BIND_HOST` / `STRATEGY_SOCKET_PORT`。
- Frame: `StrategySocketFrame.Subscribe`、`StrategySocketFrame.Snapshot(StrategyWireSnapshot)`、`StrategySocketFrame.Command`、`StrategySocketFrame.Ack`；payload 使用 `JsonElement`，避免 contract 直接依赖前端 DTO。
- 当前 topic: `INTRADAY`、`POSITIONS`、`POSITION_TRACKING`、`HEALTH`。
- 当前方向: service -> Ktor 是 SERVICE_OWNED_TOPICS（`INTRADAY` / `POSITIONS` / `POSITION_TRACKING`）的唯一传输方向；Ktor 不再反向回灌任何 owned topic，service 收到外部对 owned topic 的写入会被忽略并在 `HEALTH` 上发布 `SNAPSHOT_IGNORED_OWNED_TOPIC`。Ktor 订阅 service current/observe 推送 SYNC/UPDATE，service `POSITIONS` 通过 `updateFromService` 同步本地 Holder 作为 last-known cache，service 不可达时订阅返回 `WsAction.ERROR`，不再有任何本地 Provider/Projection/facade fallback。

目标 topic:

```
strategy.intraday.snapshot
strategy.positions.snapshot
strategy.position-tracking.snapshot
strategy.health.snapshot
strategy.command.ack
strategy.error
```

建议 command:

```
strategy.refresh-intraday
strategy.rebuild-date
strategy.rebuild-range
strategy.pause
strategy.resume
strategy.shutdown
strategy.health-check
```

目标启动和重连语义:

1. `ktor-server` 启动后连接 strategy-service。
2. 订阅 health 和业务 snapshot。
3. 收到第一帧完整 snapshot 后，对外前端 WS 可推 `SYNC`。
4. strategy-service 重启时，Ktor 保留 last-known snapshot。
5. 新 service ready 后通过新的 `sourceInstanceId` 和递增 version 接管。
6. 如果 contract version 不兼容，Ktor 拒绝切换业务 snapshot，保留 last-known，并通过 health snapshot 暴露 error。

## 6. Ktor Server 目标边界

Ktor 最终只保留外部适配职责:

```
frontend WS / REST
  -> Ktor auth/session/routing
  -> StrategyRuntimeClient
  -> strategy-service snapshot/command
```

Ktor 不再承担:

- 盘中选股计算。
- 盘中情绪计算。
- 盘后策略确认计算。
- strategy confirmed result 写库。
- strategy provider warmup。

Ktor 仍然承担:

- 前端 `/ws/app-stream` 多路复用。
- 前端 REST API。
- 用户鉴权。
- 把内部 strategy snapshot 投影为前端 `model.ws` DTO。
- 读确认结果表服务历史 REST 查询。

## 7. Agent 迭代链路

最终业务目标:

```
Agent
  -> 修改 strategy-server/core 或 strategy-server/service
  -> 跑 strategy-server/testing 回放与 contract test
  -> 构建 strategy service
  -> 重启 strategy service
  -> Ktor 不中断
  -> 前端继续收到 last-known 或新版本 snapshot
```

Agent 工作边界:

- Agent 可以独立迭代 `strategy-server/` 大模块。
- Agent 不应修改 Ktor 外部 WS 协议，除非 contract 明确需要升级。
- Agent 发布 strategy-service 时必须先通过 contract compatibility test。
- Agent 重启 strategy-service 不应影响 Ktor 进程、用户 session、前端连接。

## 8. 数据库边界

确认结果表仍在 `stock_db`，但业务 owner 迁移到 strategy-service。

| 表 | 最终写入方 | 读取方 |
| --- | --- | --- |
| `daily_stock_factor` | strategy-service | strategy-service, ktor-server REST |
| `daily_market_sentiment` | strategy-service | strategy-service, ktor-server REST |
| `daily_market_sentiment_state` | strategy-service | strategy-service |
| `sentiment_runtime_seed` | strategy-service | strategy-service |
| `daily_target_portfolio` | strategy-service | strategy-service, ktor-server REST |
| `daily_strategy_audit` | strategy-service | strategy-service, ktor-server REST |

Ktor 盘后策略阶段已切换为 service-only: `HistoricalDataUpdateOrchestrator` 只通过 `StrategyCommand.RebuildDate(date)` / `StrategyCommand.RebuildRange(start, end)` 驱动 service；service 不可达时入补偿队列指数退避重试，仍只通过 service 重做。Ktor 不再持有任何盘后本地 fallback 路径，`StrategyPreparationFacade` / `DailyStrategyDataPreparationJob` 已物理删除。

```
StrategyCommand.RebuildDate(date)
StrategyCommand.RebuildRange(start, end)
```

由 strategy-service 执行并写库。

## 9. 既有业务流程影响面

本节用于迁移前评估影响范围。任何实现阶段进入代码修改前，都应按这里逐项检查上下游、部署和维护流程。

### 9.1 盘后数据更新流水线

原始链路（已迁移，仅作历史对照）:

```
HistoricalDataUpdateOrchestrator
  -> stock daily facts
  -> qfq refresh
  -> StrategyPreparationFacade
  -> DailyStrategyDataPreparationJob
  -> daily_* strategy tables
  -> TradingCalendar.strategyUpdated
  -> StrategyPositionHolder.refreshFromAudit()
```

当前链路:

```
HistoricalDataUpdateOrchestrator
  -> stock daily facts
  -> qfq refresh
  -> StrategyCommand.RebuildDate/RebuildRange
  -> strategy-service post-market runtime (PostMarketOrchestrator + PostMarketPreparationJob)
  -> daily_* strategy tables
  -> strategy snapshot / health
  -> Ktor/API/front-end read confirmed result
```

影响点:

- 盘后编排（700 日历史窗口 + 21 步算法链）已迁入 `:strategy-server:service:postmarket`，由 `PostMarketOrchestrator` / `PostMarketPreparationJob` 直接调用 `database` Repository 写确认结果；旧的 `StrategyPreparationFacade` / `DailyStrategyDataPreparationJob` 已物理删除。
- `TradingCalendar.strategyUpdated` 的推进时机必须仍然表示"该交易日策略确认结果已成功写入"，不能提前到 command 已发送。
- 补偿任务和流水线失败处理要能区分"日线采集失败""复权失败""strategy-service 不可用""策略计算失败"。
- 手动触发历史更新的 REST/CLI 入口已统一为"发送 strategy command 并等待 ack/health"，不再有本进程直接计算路径。
- 盘后完成后，strategy-service 已发布 `POSITIONS` snapshot；Ktor 侧 `StrategyPositionHolder` 只通过 `updateFromService` 从 service snapshot 同步 last-known cache，已删除 `refreshFromAudit()` 直读 audit 表的旧路径。

迁移检查:

- 同一交易日不能由 Ktor 和 strategy-service 两边同时写 `daily_target_portfolio`。
- `strategyUpdated` 标志必须和确认结果落库事务保持一致。
- 失败重试必须幂等，重复 rebuild 同一日期不能产生重复组合记录。

### 9.2 盘中实时投影链路

原始链路（已迁移，仅作历史对照）:

```
DataProviderBootstrap
  -> SentimentProvider / IntradayFactorProvider
  -> IntradaySnapshotProjectionService
  -> IntradayPortfolioGenerator
  -> INTRADAY_SNAPSHOT
  -> StrategyPositionHolder
  -> STRATEGY_POSITIONS
```

当前链路:

```
strategy-service
  -> Ktor DAY snapshot realtime facts + seed + confirmed factors
  -> strategy runtime
  -> strategy.intraday.snapshot / strategy.positions.snapshot
  -> Ktor StrategyRuntimeClient
  -> frontend INTRADAY_SNAPSHOT / STRATEGY_POSITIONS
```

影响点:

- `SentimentProvider` / `IntradayFactorProvider` / `IntradaySnapshotProjectionService` / `ProviderActivationService` 等 Ktor 端 fallback 链路已物理删除；盘中情绪、盘中选股、Provider 激活和 warmup 全部由 strategy-service 拥有。
- `StrategyPositionHolder` 只保留 `initialize()`（冷启动 last-known 加载）与 `updateFromService()`（service `POSITIONS` snapshot 同步），不再有 `refreshFromAudit` / `updateFromIntraday` 等本地写入路径。
- 盘中高频刷新节奏由 strategy-service 控制；默认 runtime loop 只在交易日 09:30-11:30 / 13:00-15:00 自动刷新，午休、盘前盘后和非交易日只做 idle check。`STRATEGY_INTRADAY_REFRESH_OUTSIDE_SESSION_ENABLED=true` 可显式允许非交易时段自动刷新，`RefreshIntraday` command 仍可人工触发一次刷新。
- strategy-service 的盘中 R 输入来自 Ktor DAY snapshot，通过本地 strategy socket 请求当前 universe 的 realtime candle；strategy-service 不直接访问 Tushare，也不自建 rt_k 轮询。情绪和因子实时入口都必须通过 `PreparedBarFactory`。QFQ runtime bar 使用 source/baseline trade date 的 adj 生成 QFQ 字段，HFQ runtime bar 使用 firstAdj。

迁移检查:

- 前端订阅 `INTRADAY_SNAPSHOT` 时，Ktor 必须能在 strategy-service 暂不可用时返回明确 ERROR；不再有 last-known / Provider 兜底。
- strategy-service 重启期间，Ktor 前端 WebSocket 连接不能断；service 恢复后自动 SYNC 接管。
- 盘中 projection 仍然不能写 `daily_target_portfolio` 作为确认结果。

### 9.3 前端 REST / WebSocket 业务流程

当前前端入口:

- 情绪页通过 REST 读取历史情绪曲线，通过 `INTRADAY_SNAPSHOT` 读取当前投影。
- K 线页通过 `STRATEGY_POSITIONS.newlySelected` 展示策略选股列表。
- 策略跟踪页通过 `STRATEGY_POSITION_TRACKING` 读取持仓跟踪时间线；Ktor 订阅消费 strategy-service `POSITION_TRACKING` snapshot，service 不可达时返回 ERROR。

迁移原则:

- 前端 `/ws/app-stream`、`WsTopic`、`WsCommand` 尽量不随内部服务化变化。
- Ktor 继续维护前端多路复用、鉴权、订阅恢复和 command 收敛。
- 内部 socket topic 不直接暴露给前端。

影响点:

- `INTRADAY_SNAPSHOT` 的数据来源已切到 `StrategyRuntimeClient.observeIntraday()`，本地 Provider snapshot 路径已物理删除。
- `STRATEGY_POSITIONS` 的数据来源已切到 `StrategyRuntimeClient.observePositions()`；Holder 仅作为 service positions 的 last-known cache，由 `updateFromService` 写入。
- `STRATEGY_POSITION_TRACKING` 由 strategy-service 在 `POSITIONS` 产生后同步发布；Ktor 仅消费 service `POSITION_TRACKING`，不再有本地 tracking fallback。若 service socket 可用但尚无 current tracking snapshot，Ktor 必须先给前端返回明确 `ERROR` 首帧，并继续监听后续 service snapshot，避免前端订阅无响应。
- REST `/strategy/sentiment`、`/strategy/positions` 仍可读确认表，写入 owner 已统一迁出到 strategy-service。

迁移检查:

- 前端 DTO 字段保持兼容，尤其是 `rankScore` 到 `selectionScore` 的过渡映射。
- `SYNC` 和 `UPDATE` 仍然发送完整可消费视图。
- 断线重连、页面重新订阅、多个页面共同订阅时，Ktor 不触发重复策略计算。

### 9.4 数据库、schema 和迁移维护

影响点:

- strategy-service 和 Ktor 会共享读取 `stock_db`，但只有 strategy-service 写 strategy 确认表。
- 表结构、migration、repository 仍可保留在 `database` 模块，避免 service 和 Ktor 分叉维护 schema。
- 若 `:strategy-server:core` 抽离纯算法，需要把 Exposed repository 依赖放在 `:strategy-server:service` 或 `database` adapter 中，不能让 core 直接依赖 transaction。
- 滚动状态 JSON、seed JSON、snapshot payload 都需要 version 或兼容策略。

迁移检查:

- migration 仍由单一启动方执行，不能 Ktor 和 strategy-service 启动时并发跑 schema bootstrap。
- 如保留 Ktor 自动建表，strategy-service 启动前必须确认 schema ready；如迁移到独立 migrator，部署流程必须显式执行 migration。
- 所有 backfill/rebuild 命令必须能安全重复执行。

### 9.5 部署、启动和运行配置

当前部署包已包含 Ktor 主进程和基础 `strategy-service` 独立进程。迁移后至少存在两个运行时:

```
ktor-server
strategy-service
```

影响点:

- Gradle 需要新增 `:strategy-server:service` distribution/package 任务。
- `deploy.sh strategy-service deploy|restart|rollback|health|logs|status|stop|start [mode]` 支持只构建/重启/回滚 strategy-service，不影响 Ktor 主进程；全量 `deploy.sh debug|debug-wan|release` 仍同时部署两个进程。
- `config.yaml` 内部 strategy service 配置: host、port、mode、contractVersion；不再有 fallback 策略字段（已物理删除）。
- 启动顺序支持两种模式:
  - Ktor 先启动: 等待 strategy-service ready；service 不可达时订阅返回 ERROR。
  - strategy-service 先启动: Ktor 连接后立即订阅当前 snapshot。
- release/debug/debug-wan 模式都要定义 strategy-service 的内部地址，禁止 Ktor 硬编码。
- 日志、PID、健康检查、端口占用清理需要分别覆盖两个进程。

迁移检查:

- strategy-service 重启不应杀掉 Ktor。
- Ktor deployment package 不应强依赖 strategy-service 一定已经 ready 才能启动。
- strategy-service package 已能通过 `./deploy.sh strategy-service deploy [mode]` 独立构建并发布，发布前会备份上一版到 `build/deploy/strategy-service.rollback`，`./deploy.sh strategy-service rollback [mode]` 只回滚 service。
- contract version 不兼容时，Ktor 保留旧 snapshot 并暴露 health error，而不是崩溃；当前内部 socket 已携带 `contractVersion` 并由 client 执行兼容检查。

### 9.6 Agent 后台迭代与发布流程

目标流程:

```
Agent edits strategy-server/
  -> strategy-server/testing replay + contract test
  -> build strategy-service
  -> restart strategy-service only
  -> health check
  -> Ktor keeps serving frontend
```

影响点:

- Agent 工作目录和技能沙盒需要明确 strategy 大模块为主要迭代范围。
- Agent 不能默认修改 Ktor 外部 WS 协议；如必须修改，需要同步 shared/frontend/contract 文档。
- Agent 发布前必须跑 strategy contract compatibility test，避免新 service snapshot 破坏 Ktor client。
- Agent 需要能读取 strategy-service logs、health、last error、current contract version。

迁移检查:

- Agent 重启 strategy-service 的脚本不能复用 Ktor `start.sh` 直接停全量服务。
- 失败回滚通过 `./deploy.sh strategy-service rollback [mode]` 只回滚 strategy-service，不影响 Ktor 当前用户 session。
- strategy-service 启动失败时，Ktor 继续以 last-known snapshot 服务前端。

### 9.7 运维观测和故障语义

新增故障类型:

| 故障 | Ktor 对外行为 | 维护动作 |
| --- | --- | --- |
| strategy-service 未启动 | 前端 strategy topic 返回 warming/error 或 last-known | 启动 service，检查 internal socket |
| internal socket 断开 | 保留 last-known snapshot，health 标记 disconnected | 重连或重启 service |
| contract version 不兼容 | 拒绝切换新 snapshot，暴露 health error | 回滚 service 或升级 Ktor client |
| 盘后 rebuild 失败 | 确认结果不推进，`strategyUpdated=false` | 查看 strategy-service post-market logs |
| 盘中 realtime source 缺失 | snapshot 降级到 H/last-known | 检查 realtime DAY facts |

新增观测指标:

- strategy-service process health。
- internal socket connected/disconnected。
- snapshot topic version、publishedAt、sourceInstanceId。
- last successful post-market tradeDate。
- last rebuild command status。
- current contract version。
- Ktor last-known snapshot age。

### 9.8 测试矩阵

迁移后测试不能只跑 Ktor build，需要分层验证:

| 层级 | 必测内容 |
| --- | --- |
| `:strategy-server:core` | 因子/情绪/组合选择单元测试，历史回放 |
| `:strategy-server:contract` | serialization/golden test，version compatibility |
| `:strategy-server:service` | post-market rebuild，intraday replay，snapshot publish |
| `:strategy-server:client` | local/remote client，断线重连，last-known fallback |
| `ktor-server` | 前端 WS topic 投影，REST 读确认结果，health/error 语义 |
| end-to-end | strategy-service 重启时 Ktor 不重启、前端连接不断 |

## 10. 迁移阶段

### Phase 0: 当前状态收束

目标:

- 先不拆 Gradle module。
- 修正当前职责外溢。

动作:

- `IntradaySnapshotProjectionService` 只做 DTO 投影（Phase 0 阶段动作；该服务在 Phase 3 服务化完成后已物理删除，盘中投影由 service `INTRADAY` snapshot 直接驱动）。
- 新增 Ktor 内部 `StrategyRuntimeFacade`，承接盘中组合生成（Phase 0 阶段动作；最终 owner 已迁入 strategy-service）。
- 内部模型统一 `selectionScore` 语义。
- 文档明确 `INTRADAY_SNAPSHOT` 是 strategy runtime snapshot。

### Phase 1: 抽 `:strategy-server:contract`

目标:

- 先稳定内部协议。

动作:

- 新建 `strategy-server/contract`。
- 定义 snapshot envelope、topic、command、health。
- Ktor 侧新增 `StrategyRuntimeClient` 接口。
- 当前实现仍可走 local adapter。

验收:

- Ktor 不再直接依赖具体 strategy runtime 服务类，而是依赖 contract/client。

当前状态: 已完成基础 contract 与 Ktor embedded-mode local snapshot hub。

### Phase 2: 抽 `:strategy-server:core`

目标:

- 把算法与 Ktor/Exposed 解耦。

动作:

- 迁移或复制隔离纯算法模型。
- 将 Exposed repository 依赖替换为 core port。
- 建立 core 单元测试和历史回放 fixture。

验收:

- `:strategy-server:core` 不依赖 `ktor-server`。
- `:strategy-server:core` 不依赖 Exposed transaction。

当前状态: 已完成第一轮核心迁移。盘后/盘中策略计算器和选择引擎已迁入 `:strategy-server:core`；`database`、`ktor-server` 均已改为依赖 core 包。

### Phase 3: 建 `:strategy-server:service`

目标:

- service 仍在同仓库构建，但可独立运行。

动作:

- 增加 service main。
- 接入 database、realtime DAY fact source、snapshot publisher。
- 支持 health、snapshot stream、command。
- Ktor 使用 `SocketStrategyRuntimeClient` 作为 SERVICE_OWNED_TOPICS 唯一入口。

验收:

- 重启 strategy-service 不需要重启 Ktor。
- Ktor 前端 WS 连接不断。

当前状态: Phase 3 已完成。`strategy-service` 是独立进程入口，监听本机 TCP JSONL，维护 service 侧内存快照，通过同一条本地 strategy socket 从 Ktor DAY snapshot 读取 realtime fact，并结合数据库确认事实生成 `INTRADAY` / `POSITIONS` / `POSITION_TRACKING` snapshot；strategy-service 不直接轮询 Tushare。盘中情绪和因子 realtime bar 均经 `PreparedBarFactory` 统一价格口径后进入策略内核。service 会向订阅方回放 current snapshot、广播后续 snapshot、发布 `HEALTH` snapshot。盘中 runtime loop 默认按交易日交易时段自动刷新，午休/盘前盘后/非交易日暂停自动计算；`RefreshIntraday` command 触发盘中 runtime 重新取事实并发布 snapshot；`RebuildDate` / `RebuildRange` 触发 service 侧盘后 runtime（`PostMarketOrchestrator` / `PostMarketPreparationJob` / `PostMarketRebuildPolicy`）执行 700 日历史窗口 + 21 步算法链，直接写确认结果并发布 `POSITIONS` / `POSITION_TRACKING`。Ktor 已切换为单 owner adapter: 三个前端 topic 都只通过 `SocketStrategyRuntimeClient` 消费 service snapshot，同时处理 service 发起的 `LoadRealtimeDailyCandles` command；service 不可达时返回 `WsAction.ERROR`，无任何本地 Provider/Projection/facade fallback 路径，`StrategyRuntimeBridge` 不持有 SERVICE_OWNED_TOPICS 的 publish API 或本地 hub 字段，`StrategyPositionHolder` 只保留 `initialize()` 与 `updateFromService()` 写入路径。盘后 `HistoricalDataUpdateOrchestrator` 只通过 `StrategyCommand.RebuildDate` 驱动 service，service 不可达时入补偿队列指数退避重试，仍只通过 service 重做。部署包已包含 `strategy-service` application distribution 与 `start-strategy-service.sh`，`deploy.sh` 会先尝试启动 strategy-service 再启动 Ktor，strategy-service 启动失败不会阻断 Ktor 启动（订阅返回 ERROR 直至 service 恢复）；`./deploy.sh strategy-service deploy|rollback [mode]` 支持只发布/回滚 strategy-service。

### Phase 4: Agent 独立迭代

目标:

- Agent 可以只迭代 strategy 大模块。

动作:

- 建 `strategy-server/testing`。
- 继续把 strategy-service 单独发布/回滚流程固化到 Agent 工作流；当前已有 Gradle distribution、`./deploy.sh strategy-service deploy|rollback [mode]`、独立启停脚本和 `start-strategy-service.sh health` socket HealthCheck。
- Agent workflow 固化: edit -> replay/test -> package -> restart service -> health check。

验收:

- strategy-service 更新不影响 Ktor 进程。
- contract version 不兼容时，Ktor 自动拒绝切换并保留 last-known snapshot；当前已通过内部 socket `contractVersion` 检查落地。

## 11. 目录命名规则

采用大模块目录 `strategy-server/`，内部子模块按职责命名，不使用多个顶层 `strategy-*`:

推荐:

```
strategy-server/contract
strategy-server/core
strategy-server/service
strategy-server/client
strategy-server/testing
```

不推荐:

```
strategy-contract
strategy-core
strategy-service
strategy-client
```

例外:

- 当前已有空目录 `strategy-server/`，后续应合并或迁移为 `strategy-server/service`，避免长期并存。

## 12. 与现有文档的关系

- 情绪/选股业务语义仍以 `.claude/skills/sentiment-selection-architect/references/sentiment-selection-strategy-flow.md` 为准。
- 当前内嵌实现链路仍以 `.claude/skills/sentiment-selection-architect/references/sentiment-selection-architecture.md` 为准。
- 本文档定义未来服务化目标和迁移边界。
- data-layer 文档继续描述 K 线、Provider、当前数据链路；不要把 strategy service 的内部协议强行并入泛化 data-layer。

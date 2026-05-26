# Strategy Service 迁移接手文档

本文档用于让后续 agent 在不中断上下文的情况下接手 strategy 服务化迁移。当前目标不是重新设计，而是沿着已确认的方案继续把 strategy 的业务主权从 `ktor-server` / `database` 收束到独立 `strategy-service`。

## 1. 接手前必须读取

先按项目本地 skill 读取并遵守以下文件:

- `.claude/skills/sentiment-selection-architect/SKILL.md`
- `.claude/skills/sentiment-selection-architect/references/sentiment-selection-architecture.md`
- `.claude/skills/sentiment-selection-architect/references/sentiment-selection-strategy-flow.md`
- `.claude/skills/database-runtime-architect/SKILL.md`
- `.claude/skills/database-runtime-architect/references/database-runtime-architecture.md`
- `.claude/skills/deployment-architect/SKILL.md`
- `.claude/skills/deployment-architect/references/deployment-architecture.md`
- `docs/architecture/strategy-service-architecture.md`

接手时不要脱离业务链路做纯目录整理。每个改动都要能回答: 它影响盘后确认、盘中投影、Ktor 对前端输出、部署重启、agent 后台迭代中的哪一段。

## 2. 最终业务目标

strategy 模块的业务范围定义为:

- 盘中情绪
- 盘中选股
- 盘后情绪
- 盘后选股

目标运行形态:

```text
strategy-service 独立进程
  -> 拥有 strategy runtime 生命周期
  -> 生产 INTRADAY / POSITIONS / POSITION_TRACKING / HEALTH snapshot
  -> 接受 refresh / rebuild / health command
  -> 可被 agent 单独迭代、部署、重启

ktor-server
  -> 不再拥有 strategy 主流程
  -> 只做前端 REST/WS adapter、auth/session、内部 socket client
  -> strategy-service 重启时尽量保持前端连接和 last-known snapshot

database
  -> 保留 Exposed Table / Repository / schema / datasource 实现
  -> 不再承载纯算法内核
```

## 3. 当前已落地状态

### 3.1 Gradle 与模块结构

已采用 `strategy-server` 父目录 + 子模块:

```text
strategy-server/
  contract/
  core/
  client/
  service/
  testing/
```

对应 Gradle project:

- `:strategy-server:contract`
- `:strategy-server:core`
- `:strategy-server:client`
- `:strategy-server:service`
- `:strategy-server:testing`

不要再新增顶层 `strategy-*` 平铺模块。`contract` 虽然小，但它是 Ktor 与独立 service 的进程间协议防火墙，保留独立模块是有意义的。

### 3.2 已迁移到 core 的纯策略内核

纯算法内核已从 `database` 迁到:

```text
strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/
  daily/
  daily/preprocessing/
  intraday/
  sentiment/
```

包名已收束为 `org.shiroumi.strategy.core.*`。

`database` 现在依赖 `:strategy-server:core`，继续保留策略表、Repository、schema/bootstrap、盘后编排和 seed 映射。

### 3.3 已落地的 contract / client / service 骨架

`strategy-server/contract` 已定义:

- `StrategyTopic`
- `StrategySnapshotEnvelope`
- `StrategyWireSnapshot`
- `StrategySocketFrame`
- `StrategyCommand`
- `StrategyCommandAck`
- `StrategySnapshotSource`
- `StrategySnapshotSink`
- `StrategyRuntimeClient`

`strategy-server/client` 已有:

- `LocalStrategySnapshotHub`
- `SocketStrategySnapshotPublisher`
- `SocketStrategyRuntimeClient`

`strategy-server/service` 已有独立 socket 进程:

- 入口: `StrategyServiceMain.kt`
- 默认监听: `127.0.0.1:9971`
- 支持 JSONL socket frame
- 接收 Ktor 发布的 snapshot
- 维护 service 内存 snapshot
- 支持订阅 current replay + 后续 broadcast
- 发布 `HEALTH`
- 支持 command ack
- 内部 socket frame、snapshot 和 ack 携带 `contractVersion`；Ktor socket client 会拒绝不兼容业务 snapshot，保留 last-known，并发布 `CONTRACT_VERSION_MISMATCH` health snapshot。
- 已新增盘中 runtime: service 侧只读主板 universe、seed、rolling state、Ktor DAY snapshot realtime fact 和策略确认结果，主动生产 `INTRADAY` / `POSITIONS` snapshot；realtime fact 通过本地 strategy socket 从 Ktor snapshot 读取并进入短 TTL cache，strategy-service 不直接轮询 Tushare；盘中 projection 不写 `daily_*` 确认结果表。
- service 是 SERVICE_OWNED_TOPICS（`INTRADAY` / `POSITIONS` / `POSITION_TRACKING`）的唯一 owner，外部 socket 写入这些 owned topic 会被忽略并在 `HEALTH` 上发出 `SNAPSHOT_IGNORED_OWNED_TOPIC`。
- `RefreshIntraday` command 已触发 service runtime 刷新；`RebuildDate` / `RebuildRange` 已接入 service 侧盘后 runtime（`PostMarketOrchestrator` / `PostMarketPreparationJob` / `PostMarketRebuildPolicy`），直接调用 `database` Repository 写确认结果，并在成功后发布 `POSITIONS` / `POSITION_TRACKING` snapshot。

注意: service 已是 SERVICE_OWNED_TOPICS 的唯一 owner，并通过本地 strategy socket 从 Ktor `CandleSnapshotManager` DAY snapshot 读取 realtime fact，经 `PreparedBarFactory` 统一价格口径再进入情绪/因子投影；盘后 700 日历史窗口 + 21 步算法链已迁入 `:strategy-server:service:postmarket`。Ktor 端 fallback 链路（`SentimentProvider` / `IntradayFactorProvider` / `IntradaySnapshotProjectionService` / `ProviderActivationService` / `StrategyRuntimeFallbackPolicy` 等）已物理删除，6 个 `STRATEGY_KTOR_*_FALLBACK_*` 环境变量已在物理删除链路时一并移除，service 不可达时订阅返回 `WsAction.ERROR`。基础独立部署已接入 `deploy.sh` 和 `start-strategy-service.sh`，`./deploy.sh strategy-service deploy|rollback [mode]` 支持只发布/回滚 strategy-service。

### 3.4 Ktor 当前接入状态

Ktor 已新增:

```text
ktor-server/src/main/kotlin/org/shiroumi/server/runtime/strategy/StrategyRuntimeBridge.kt
```

当前行为:

- `IntradaySnapshotSubscriptionService` / `StrategyPositionSubscriptionService` / `StrategyPositionTrackingSubscriptionService` 都是 service-only，只通过 `SocketStrategyRuntimeClient` 消费 service current/observe snapshot。
- service snapshot 不可用时，三个前端订阅直接返回 `WsAction.ERROR`，没有任何本地 Provider/Holder/tracking fallback 路径。
- `STRATEGY_POSITIONS` 的 remote 首包和后续 update 通过 `updateFromService` 写入 Ktor `StrategyPositionHolder`，仅作为 last-known cache；`STRATEGY_POSITION_TRACKING` 直接读取 service `POSITION_TRACKING` snapshot。
- Ktor 端 `StrategyRuntimeBridge` 不再持有 SERVICE_OWNED_TOPICS 的 publish 路径或本地 hub 字段（`publishIntraday` / `publishPositions` / `intradaySnapshots` / `positionSnapshots` / `remotePublisher` 已物理删除），只保留 `currentRemote*` / `observeRemote*` / `sendCommand` / `rebuildPostMarketDate`。
- `STRATEGY_SOCKET_CONSUME_ENABLED=false` 时才禁用 Ktor 订阅 service snapshot；默认会尝试连接 `127.0.0.1:9971`。`STRATEGY_KTOR_*_FALLBACK_*` 系列环境变量（盘中、策略持仓、跟踪、盘后、反向 publish）已在物理删除链路时一并移除，不再可配置。

## 4. 真实业务链路边界

### 4.1 盘后确认链路

```text
日线事实 / 复权事实
  -> PreparedBarFactory
  -> StockFactorCalculator
  -> MarketSentimentCalculator
  -> TargetPortfolioGenerator
  -> StrategyAuditGenerator
  -> daily_stock_factor / daily_market_sentiment / daily_target_portfolio / daily_strategy_audit
  -> sentiment_runtime_seed
```

盘后确认结果必须落库。后续迁移时，`strategy-service` 应成为盘后编排 owner，但 database 仍提供 repository / transaction / schema 实现。

### 4.2 盘中投影链路

```text
T-1 rolling state + sentiment_runtime_seed
  + T 日 realtime DAY facts
  -> IntradayFactorCalculator
  -> IntradaySentimentCalculator
  -> IntradayPortfolioGenerator
  -> strategy snapshot
  -> Ktor frontend WS adapter
```

盘中结果是 runtime projection，不写入长期确认结果表。缺少 seed、rolling state、realtime candle、adj/firstAdj 时要回退 H 轨或保留 last-known，不能用错误 RAW 事实污染策略。

### 4.3 Snapshot 边界

snapshot 是高级抽象，底层可以是:

- 本地内存 hub
- socket 订阅另一个进程的内存
- 后续更换为更高性能 IPC

Ktor 和前端不应该感知底层实现变化。

## 5. 预期内仍未落地的工作

优先级按业务主权迁移排序。

### P0: 让 `strategy-service` 接管盘中 runtime

当前已完成接管: service 可通过本地 strategy socket 读取 Ktor DAY snapshot realtime fact，与数据库确认事实共同生成并发布 `INTRADAY` / `POSITIONS` / `POSITION_TRACKING` snapshot，并已有 service runtime 测试覆盖正常发布、seed 缺失回退、HFQ firstAdj 缺失保守降级和 tracking 发布。仍待继续强化:

- 已将当前数据库日线最小 fact adapter 替换为 Ktor snapshot socket fact source，避免依赖盘后日线表近似实时事实，同时避免 strategy-service 自建外部行情轮询。
- 继续补齐 partial realtime、version 递增和 socket 级 command 到 runtime 的集成测试。
- 已接入交易时段刷新治理: 默认只在交易日 09:30-11:30 / 13:00-15:00 按 `STRATEGY_INTRADAY_REFRESH_INTERVAL_MS` 自动刷新；午休、盘前盘后和非交易日按 `STRATEGY_INTRADAY_IDLE_CHECK_INTERVAL_MS` 做 idle check。`STRATEGY_INTRADAY_REFRESH_OUTSIDE_SESSION_ENABLED=true` 可显式允许非交易时段自动刷新。

### P0: Ktor 前端 topic 改为 service-only ✅ 已完成

目标已落地:

```text
前端 WS
  -> Ktor subscription service
  -> StrategyRuntimeBridge.SocketStrategyRuntimeClient
  -> strategy-service snapshot
```

Ktor 已是单 owner adapter，三个前端 topic 都只通过 `SocketStrategyRuntimeClient` 消费 service snapshot；service 不可达时返回 `WsAction.ERROR`，无任何本地 Provider/Projection/facade fallback 路径，`StrategyRuntimeBridge` 不持有 SERVICE_OWNED_TOPICS 的 publish API 或本地 hub 字段。`KtorSingleOwnerSubscriptionTest` 反射契约测试在 `ktor-server/src/test/kotlin/.../strategy/` 下锁定该约束。

### P1: 让 command 真正驱动业务

当前 command protocol 已有，`HealthCheck`、`RefreshIntraday`、`RebuildDate`、`RebuildRange` 均已接到 service runtime。

仍需强化:

- command ack 要携带明确成功/失败原因，失败不能被 Ktor 解释为静默成功。
- command 到 runtime 的 socket 集成测试。
- `RebuildRange` 的大区间吞吐与失败恢复策略。

### P1: 盘后编排 owner 已迁移 ✅ 已完成

目标已落地:

- 盘后 700 日历史窗口 + 21 步算法链已迁入 `:strategy-server:service:postmarket`（`PostMarketOrchestrator` / `PostMarketPreparationJob` / `PostMarketRebuildPolicy`）；`PostMarketStrategyRuntime.executeTradeDates` 直接调用 service 内部 owner，不再委托 `database.StrategyPreparationFacade`。
- `database` 不再持有业务编排主权，只保留 Repository / Schema / DataSource 实现；旧的 `StrategyPreparationFacade` / `DailyStrategyDataPreparationJob` 已物理删除。
- `HistoricalDataUpdateOrchestrator` 只通过 `StrategyCommand.RebuildDate` 驱动 service；service 不可达时入补偿队列指数退避重试，仍只通过 service 重做，无任何本地 fallback。

### P2: 独立部署与重启闭环 ✅ 基础已落地

已落地:

- `strategy-service` application distribution 随 Ktor deploy 包复制到 `build/deploy/strategy-service`。
- `start-strategy-service.sh` 支持 start / stop / restart / status / health / logs，使用独立 PID 和 stdout log；`health` 会通过内部 socket 发送 `HealthCheck` command。
- `deploy.sh` 停旧实例时分别处理 Ktor 与 strategy-service，并在启动时先尝试启动 strategy-service，再启动 Ktor；strategy-service 启动失败只告警，不阻断 Ktor（策略订阅会返回 ERROR 直至 service 恢复）。
- `config.yaml` / `config.example.yaml` 中 `strategy.strategyServiceHost`、`strategy.strategyServiceBindHost`、`strategy.strategyServicePort` 是默认内部 socket 地址；环境变量仍可覆盖。
- `./deploy.sh strategy-service deploy|restart|rollback|health|logs|status|stop|start [debug|debug-wan|release]` 支持只构建/发布/回滚/操作 strategy-service，不重启 Ktor。
- Ktor 在 service 重启期间的重连验证: `SocketStrategyRuntimeClientTest` + `KtorSingleOwnerSubscriptionTest` + `StrategyServiceFirstIntegrationTest` 已覆盖断线/重连/SYNC 接管语义。

### P2: 测试补强

已落地:

- socket client 回归测试 (`SocketStrategyRuntimeClientTest`)。
- service-first 三 topic 集成测试 (`StrategyServiceFirstIntegrationTest`)。
- Ktor 单 owner 反射契约测试 (`KtorSingleOwnerSubscriptionTest`) — 锁定 §6 关键约束 "ktor 不应产生任何 SERVICE_OWNED_TOPICS 的 snapshot, 只能消费 service"。
- 盘后 owner 测试 (`:strategy-server:service` 下 `PostMarketOrchestratorTest` + `PostMarketStrategyRuntimeTest`)。

仍可补强:

- service restart 后 `sourceInstanceId` 切换测试。
- snapshot version 递增和 last-known 行为测试。
- `RebuildRange` 大区间吞吐与失败恢复测试。

## 6. 关键实现约束

- `strategy-server:core` 不允许依赖 Ktor、Exposed transaction、socket、server runtime。
- `strategy-server:contract` 不允许依赖 Ktor、Exposed、具体 socket 实现。
- `ktor-server` 不应继续新增策略算法或策略主流程，只能新增 adapter / bridge / subscription 兼容逻辑。
- **Ktor 不应产生任何 SERVICE_OWNED_TOPICS（`INTRADAY` / `POSITIONS` / `POSITION_TRACKING`）的 snapshot，只能消费 service**。这是 ktor 单 owner 边界的硬约束，由 `KtorSingleOwnerSubscriptionTest` 反射契约测试锁定。
- `database` 可以继续作为 repository / datasource implementation，但纯算法不要放回 `database`；盘后编排 owner 已在 `:strategy-server:service:postmarket`，不要重新引入 `database` 端的策略 facade。
- 盘后确认结果可以写 DB；盘中 projection 不写确认结果表。
- 盘后和盘中选股必须复用 `PortfolioSelectionEngine` 口径。
- `rankScore` 是单股因子分，`selectionScore` 是最终横截面选股分，不能混用。
- `PreparedBarFactory` 是价格口径入口；盘中情绪和因子 realtime bar 都必须经它进入策略内核，不能把 raw realtime close 直接塞进策略内核。
- 订阅路径只读 snapshot，不做重型全市场计算；service 不可达时直接返回 `WsAction.ERROR`，不允许重新引入 Provider/Holder 本地兜底。

## 7. 已验证过的命令

最近一次迁移后已验证通过:

```bash
./gradlew :strategy-server:contract:compileKotlin :strategy-server:client:compileKotlin :strategy-server:service:compileKotlin :ktor-server:compileKotlin
./gradlew :strategy-server:testing:test --tests '*SocketStrategyRuntimeClientTest'
./gradlew :database:test --tests '*TargetPortfolioGeneratorTest' --tests '*IntradaySentimentCalculatorTest' --tests '*SentimentRuntimeSeedBuilderTest'
```

后续 agent 修改后至少跑与改动面对应的测试。若涉及部署脚本，还要按 deployment skill 跑对应 packaging / status 验证。

## 8. 建议下一步执行顺序

主要迁移已完成（PR1 + PR2: 服务端真 owner + Ktor 单 owner 切换 + fallback 链路物理删除）。后续接手 agent 在改动前请按以下顺序确认基线:

1. 先确认当前工作区能编译，避免在半迁移状态上叠加新问题。
2. 跑 `KtorSingleOwnerSubscriptionTest` 与 `StrategyServiceFirstIntegrationTest` 验证 ktor 单 owner 边界仍然保持。
3. 跑 `PostMarketOrchestratorTest` 与 `PostMarketStrategyRuntimeTest` 验证盘后 owner 仍在 `:strategy-server:service:postmarket`。

已完成的清理批次:

- `database` 模块下原 `StrategyPreparationFacade.kt` / `DailyStrategyDataPreparationJob.kt` 已物理删除。
- `DerivedDataPorts.kt` / `DerivedDataAdapters.kt` 等 dead code 已物理删除。
- `findSelectedSymbols` / 其它 database 内未被任何 caller 使用的策略读 helper 已物理删除。

已完成的非阻塞清理:

- `DerivedSentimentFields` 已完全清理，无跨模块残留。
- `StrategyAuditSummary` 已迁出至 `strategy-server:core/audit/`，不再依赖 database 模块。

## 9. 给下一个 agent 的提示词

```text
你现在接手 /Users/zhouzheng/Code/quant 的 strategy 服务化迁移后续工作。

请先读取并遵守 AGENTS.md，以及以下项目本地 skill:
- .claude/skills/sentiment-selection-architect/SKILL.md
- .claude/skills/database-runtime-architect/SKILL.md
- .claude/skills/deployment-architect/SKILL.md

然后读取:
- docs/architecture/strategy-service-architecture.md
- docs/architecture/strategy-service-agent-handoff.md
- .claude/skills/sentiment-selection-architect/references/sentiment-selection-architecture.md
- .claude/skills/sentiment-selection-architect/references/sentiment-selection-strategy-flow.md
- .claude/skills/database-runtime-architect/references/database-runtime-architecture.md
- .claude/skills/deployment-architect/references/deployment-architecture.md

主要迁移已完成:
- strategy-server 是父目录，下面保留 contract/core/client/service/testing 子模块。
- core 已承载纯策略算法。
- contract 是 Ktor 与 strategy-service 的进程间协议防火墙。
- service 是 SERVICE_OWNED_TOPICS（INTRADAY/POSITIONS/POSITION_TRACKING）唯一 owner，包括盘后 700 日历史窗口 + 21 步算法链（PostMarketOrchestrator/PostMarketPreparationJob/PostMarketRebuildPolicy）。
- ktor 是纯 adapter，service 不可达时三个前端订阅返回 WsAction.ERROR。
- Ktor 端 fallback 链路（SentimentProvider/IntradayFactorProvider/IntradaySnapshotProjectionService/ProviderActivationService/StrategyRuntimeFallbackPolicy 等）已物理删除，6 个 STRATEGY_KTOR_*_FALLBACK_* 环境变量已一并移除。

实现时必须保护这些边界:
- Ktor 不应产生任何 SERVICE_OWNED_TOPICS 的 snapshot，只能消费 service（KtorSingleOwnerSubscriptionTest 反射契约测试已锁定该约束）。
- 盘后确认结果可以写 daily_* 策略表；盘中 projection 不写确认结果表。
- 盘后和盘中选股必须复用 PortfolioSelectionEngine。
- PreparedBarFactory 是价格口径入口，盘中情绪和因子 realtime bar 都必须经它进入策略内核，不能把 raw realtime close 直接塞进策略内核。
- 订阅路径不能触发全市场重算，只能读 snapshot；service 不可达时直接返回 WsAction.ERROR，不允许重新引入 Provider/Holder 本地兜底。
- 不要在 database 模块重新引入策略 facade 或编排逻辑；盘后编排 owner 在 :strategy-server:service:postmarket。
- 不要回滚当前工作区已有迁移改动。

仍可清理的非阻塞残留（如果你的任务包含清理）:
- DerivedSentimentFields internal 跨模块归属问题。
- StrategyAuditSummary 是否从 database 迁出（当前留作 row 投影 data class，service runtime 仍在使用）。

开始前先运行 git status --short 和必要的 rg 了解当前状态。修改后至少验证:
./gradlew :strategy-server:contract:compileKotlin :strategy-server:client:compileKotlin :strategy-server:service:compileKotlin :ktor-server:compileKotlin
./gradlew :strategy-server:testing:test --tests '*SocketStrategyRuntimeClientTest' --tests '*StrategyServiceFirstIntegrationTest'
./gradlew :strategy-server:service:test --tests '*PostMarketOrchestratorTest' --tests '*PostMarketStrategyRuntimeTest'
./gradlew :ktor-server:test --tests '*KtorSingleOwnerSubscriptionTest'
以及与你改动相关的 database/ktor 测试。

如果改变了业务链路、模块边界、部署入口或 snapshot/command 语义，必须同步更新:
- docs/architecture/strategy-service-architecture.md
- docs/architecture/strategy-service-agent-handoff.md
- 对应 .claude/skills/*/references/*.md
```

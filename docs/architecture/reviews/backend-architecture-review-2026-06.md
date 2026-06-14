# 后端架构深度扫描与整改路线图（2026-06）

> 范围：`ktor-server` 主服务进程 + `strategy-server:*` 家族 + `database` / `network` / `agent` / `backtest` / `shared` 共 12 个后端模块。
> 方法：模块依赖图（`build.gradle.kts` 的 `project()` 边）+ 包结构（`find`/`grep` 实测）+ 三条主链路（数据 / 订阅 / 启动）。每条论断均已对当前代码逐条核验，行号与计数来自实际 `grep`/`read`/`wc`。
> 目标：① 架构是否优雅、链路统一性是否够强；② 按功能语义区分模块、耦合是否简单优雅。

---

## 0. 总评

后端**地基是优雅的**：12 个模块的依赖图无环、单向（DAG），`strategy-server` 的 `contract/core/client/service` 四分层是全仓最干净的语义分层，三条主链路（K 线数据 / 策略快照 / 启动编排）实际都已收敛到单一路径。

**不优雅集中在 `ktor-server` 这一个 17446 行的重模块里**，根因是「一次没走完的通用化迁移」留下的双层分裂：一套零实现的通用 `dataprovider` 框架空转、与旧 `data/*` 管线并存；订阅服务样板复制三遍；路由散落三处；`tool`/`tools` 同名包；外加一条 `database→core` 的真实分层倒置和一条 `ktor-server→core` 的死依赖边。

| 维度 | 评分 | 一句话 |
|---|---|---|
| 模块语义划分 | 5.5/10 | strategy-server 四分优雅；ktor-server 是杂物袋 |
| 链路统一性 | 6.5/10 | 主链路已收敛，但留了一套空转脚手架 + 订阅样板重复 |
| 模块耦合优雅 | 7.0/10 | 图无环单向；1 处真倒置 + 1 条死边 + shared 命名失序 |
| ktor-server 内聚 | 4.5/10 | 双根包、双数据层、三处路由、tool/tools 同名 |

---

## 1. 模块总览（12 个后端模块）

声明于 `settings.gradle.kts:41-55`（`compose-app`/`ksp`/`cli`/`tools:*` 为非后端模块，已排除）。

| 模块 | main LOC | 功能语义职责 | 依赖 |
|---|---|---|---|
| `shared` | 7138（KMP，commonMain 6850） | 跨端 DTO、wire 协议、配置、复权算法 | — |
| `strategy-server:contract` | 130（单文件） | 策略运行时线协议（Topic/帧/命令/快照接口） | — |
| `strategy-server:core` | 2874 | 纯策略计算内核（因子/情绪/组合/选股规则/审计） | shared |
| `strategy-server:client` | 428（3 文件） | contract 的快照传输客户端 | `api(contract)` |
| `network` | 1685 | 外部 HTTP 客户端（Tushare/LLM/消息推送 + 限流） | shared |
| `agent` | 2329 | Claude Code/ACP 集成（桥接/状态流/技能/沙盒） | shared |
| `backtest` | 4155 | 回测引擎（engine/feed/match/ledger/rule/...） | shared, database, contract |
| `database` | 7957 | Exposed 持久层（13 个领域子包） | ksp, shared, network, **⚠️ strategy-server:core** |
| `strategy-server:service` | 4679 | 策略运行时编排（盘后/盘中/预处理/universe） | contract, core, client, database, shared |
| `strategy-server:research` | 8758 | 七段研究管线 + 多 topic + tuner | shared, database, contract |
| `strategy-server:testing` | 1253（仅 test） | contract/core 契约一致性测试夹具 | contract, core, client(test) |
| `ktor-server` | 17446 | HTTP/WS 服务装配 + 数据/运行时/订阅/路由/agent | network, database, backtest, shared, client, **⚰️ core(死边)**, agent |

---

## 2. 优雅的部分（地基稳，作为正例固化）

### 2.1 依赖图无环、单向（DAG）

逐条 `project()` 边核验，全图无任一回边。唯一「层级倒置」边是 `database→core`，但 `core` 只出边到 `shared`（`core/build.gradle.kts:10`），不回指 `database`，故 `database→core→shared` 仍是 DAG，不形成环。

### 2.2 strategy-server 四分层是全仓标杆

- `contract`（130 行单文件，零项目依赖，叶子）：纯 `@Serializable` 帧/命令/快照 + `Source/Sink/Client` 接口，`STRATEGY_CONTRACT_VERSION=1`，无实现。与 `shared.model.ws`（前端通用 `WsEvent/WsCommand`）职责不重叠——contract 是策略运行时实例间的专用线协议。
- `core`（2874 行）：纯计算内核，仅依赖 `shared`。
- `client`（428 行）：`api(contract)`，仅依赖 contract。
- `service`（4679 行）：汇聚 contract+core+client+database+shared，编排层。
- 依赖单向无环：无任一模块反向依赖 `service`。

### 2.3 backtest 是边界正例

`backtest/build.gradle.kts:16-18` 仅依赖 `:shared`/`:database`/`:strategy-server:contract`，无 core/runtime/service，且第 9-14 行注释明文约束「策略零账户感知」。**与 `database→core` 倒置形成鲜明对照——同样是后端模块，backtest 守住了，database 没守住。**

### 2.4 三条主链路实际已收敛（比表面更好）

- **K 线数据平面统一在 `DataLayerBootstrap.candleFacade` 这一个 SSOT**：REST / WS / CLI 全部 `readSnapshot` 同一 facade。
- **启动编排统一在一套 `ServerLifecycleManager`**（`BOOTSTRAPPING→WARMING_UP→READY→SUBSCRIPTION_READY`）。两个 Bootstrap 实为「外层编排内层」：`DataProviderBootstrap` 把 `DataLayerBootstrap.initialize()` 当成一个 `LifecycleTask` 调起，不是平行双底座。
- **策略「双消费模式」不分叉**：独立进程 `StrategyServiceMain` + ktor 进程内 `StrategyRuntimeBridge`，owner 唯一性由 service 端 `SERVICE_OWNED_TOPICS` 强制，ktor 端是纯只读 adapter。

---

## 3. 问题清单（已逐条核验）

### P1【high】通用 `dataprovider` 框架是零实现的空壳脚手架

- **现状**：`server/dataprovider/*` 共 12 文件（contract/port/registry/provider/store/adapter/service），构成一套「通用 Provider 化」框架，但**生产运行时完全空转**：
  - `: AbstractDataProvider` 子类数 = **0**（`AbstractDataProvider.kt:28` 自身实现 contract，但零继承者）。
  - `registry.register(` 在 main 代码**零业务调用**（`DataProviderRegistry.kt:30` 仅定义，调用只存在于 test）。
  - `DataProviderRuntimeCoordinator.start()`（`DataProviderRuntimeCoordinator.kt:43-55`）把阶段流/tick 流广播到 Registry，但 `notifyPhaseChanged`/`notifyTradingTick`（`DataProviderRegistry.kt:65-69`、`77-83`）对空 `providers` map 做 `forEach`，运行时对空集合空转、不驱动任何 Provider。
- **与旧层关系**：`DataProviderBootstrap` 把核心 candle 链路全部委托回旧 `DataLayerBootstrap`——`candleFacade`（`DataProviderBootstrap.kt:56`）、`candleProjectionService`（`:57`）、`candleSubscriptionService`（`:71`）三个属性均 `by lazy` 直接取自旧层。旧 `data/provider/CandleDataProvider`（`CandleDataProvider.kt:36-39`）是无接口子句的普通 class，**不实现** `dataprovider.contract.DataProvider`，两套体系完全不互通。
- **迁移状态**：`Main.kt:98-99` 注释自述「当前阶段先只启动 runtime 和装配容器，不切 HTTP / WebSocket 消费链路」；`Main.kt:100` 唯一调 `DataProviderBootstrap.initialize()`。这不是有意分层，是**彻底搁浅的迁移**。
- **代价**：同一份 K 线供给逻辑横跨 `data/*`（8 文件）与 `dataprovider/*`（12 文件）共 20 文件；读代码要在两层间跳；新增数据 provider 要先判断落哪层；新框架长期空转占认知预算。

### P2【high】`ktor-server` 是单模块杂物袋（17446 行扛 8 类正交职责）

- `org.shiroumi.server.*` 86 文件 14218 行，下辖 13 个直接子包：`runtime`(25)、`dataprovider`(12)、`data`(8)、`share`(6)、`route`(4)、`dto`(3)、`subscription`(3)、`agent`(2)、`repository`(2)、`tool`(2)、`websocket`(2)、`service`(1)、`tools`(1)，共 71 文件，外加 **15 个散落在 `server/` 根的 `.kt`**。
- 这些是彼此正交的功能域（web 装配 / 数据供给 / 运行时编排 / 订阅推送 / HTTP 路由 / 仓储 / agent 进程管理 / ops 批处理），当前仅靠包名分隔而非模块边界隔离。
- **代价**：任一职责改动都要在 17446 行同一 Gradle 模块内编译/测试，无法单独构建或复用；`runtime`/数据供给本可下沉为独立模块被 backtest/research 复用，现被锁死在 server 里。

### P3【high】15 个 `main()` 平铺在 `server` 包根（服务进程与一次性 CLI 作业混居）

- `org/shiroumi/server/` 根直下 15 个含 `fun main` 的文件，只有 **`Main.kt`** 是真正的服务器启动（`Main.kt:47` `fun main`，`:64` `embeddedServer CIO`，全包根唯一 `embeddedServer` 命中）。
- 其余 **14 个均为 `runBlocking` 一次性运维/数据同步脚本**：`BackfillHistoricalData`、`CleanupDailyStrategyData`、`CollectOpen5m`、`CollectStock15m`、`DrainCompensationQueue`、`ExportProfitPredictionInputs`、`ResetStrategyFlag`、`SyncFundamentalQuarterly`、`SyncKplList`、`SyncMacroMonthly`、`SyncStockMoneyFlow`、`SyncTopInst`、`SyncTopList`、`UpdateDaily`。
- **代价**：服务进程 classpath 背上所有 ops 脚本的依赖；任一 ops 脚本编译失败阻塞整个 server 构建；这些批处理本应是独立 `cli`/`data-ops` 模块。

### P4【high】HTTP 路由分散在三个包根（无统一约定，纯历史落点）

- `ktor/module/routing/`：9 文件（`AppStreamRoute`/`ComposeApp`/`DataUpdateRoutes`/`DownloadRoutes`/`InternalCliRoute`/`PreloadRoutes`/`ResearchDataRoutes`/`ResumeRoute`/`StockRoutes`），1832 行，**含真实业务 handler**——`StockRoutes`(192 行，自建 `StockRepositoryImpl`、分页/排序/搜索)、`ResearchDataRoutes`(422 行，直读 8 个 DB Repository)。
- `org/shiroumi/server/route/`：4 文件（`AgentAnalysisRoutes`/`AgentConfigRoutes`/`BacktestRoutes`/`StrategyRoutes`）。
- `org/shiroumi/server/share/PublicShareRoutes.kt`：第 41 行 `fun Route.publicShareRoutes`、第 78 行挂 `/api/v1/public/share/{token}`。
- `KtorRouting.kt` 的 `routing{}` 块（第 22-63 行）汇聚四个不同包根（`ktor.auth`/`ktor.module.routing`/`server.route`/`server.share`，import 在第 6-12 行）统一挂载。
- **同构证据**：`StockRoutes.kt` 声明 `package ktor.module.routing`（`stockRoutes`@39），`StrategyRoutes.kt` 声明 `package org.shiroumi.server.route`（`strategyRoutes`@47）——同为业务 REST 路由却落在两个不相关包根。
- **代价**：找一个 endpoint 要在三处搜；`route` vs `routing` 差一个字母极易记混；新增路由放哪个包纯靠惯例。

### P5【med】分层倒置：`database → strategy-server:core`

- **边声明**：`database/build.gradle.kts:18` `implementation(project(":strategy-server:core"))`。
- **真实消费**：6 个 Repository 共 8 处 import，复用 core 的 7 个符号：
  - 纯 data class：`MarketSentimentRollingState`（`MarketSentimentRollingState.kt:41`）、`MarketSentimentSnapshot`（`MarketSentimentCalculator.kt:38`，**内嵌在计算器文件**）、`SentimentDerivedFields`（`SentimentDerivedFields.kt:43`）、`TargetPosition`（`TargetPortfolioGenerator.kt:5`）、`FactorRollingState`（`FactorRollingState.kt:13`）、`StockFactorSnapshot`（`StockFactorCalculator.kt:26`，**内嵌在计算器文件**）、`StrategyAuditSummary`（`StrategyAuditSummary.kt:5`）。
  - ⚠️ **非纯 DTO**：还复用了 `restoreSentimentDerivedFields`（`SentimentDerivedFields.kt:72`，承载情绪派生公式的顶层函数）。因此该边**并非纯 DTO 复用**——持久层连带拉取了一部分计算逻辑。
- **代价**：最底层持久层被算法层 core 反向拉住，core 领域模型一改字段连带 database 重编译；无法脱离策略算法单独用 database；违反 backtest 已守住的「database 只依赖 shared」方向。

### P6【med】死依赖边：`ktor-server → strategy-server:core` 零引用

- `ktor-server/build.gradle.kts:36` 声明 `implementation(project(":strategy-server:core"))`，但 `ktor-server/src` 全量 `grep org.shiroumi.strategy.core` 命中 = **0**（main+test 均无）。
- ktor 对策略层的真实消费已收敛到 **client+contract**：`strategy.client` 命中 1 处（`StrategyRuntimeBridge.kt:19` 的 `SocketStrategyRuntimeClient`），`strategy.contract` 命中 8 处（命令/快照/Topic 契约）。
- **代价**：删 `build.gradle.kts:36` 一行即可，编译不受影响，耦合面立即收窄到 client 单一入口。

### P7【med】4 个 WS 订阅服务样板逐字重复（无共享基类）

> 注：此处经核验修正——是 **4 个**真正的订阅服务，不是早期误报的 5 个。`CandleProjectionService`（`data/subscription/CandleProjectionService.kt`，117 行）**不是订阅服务**，无 `subscribe/unsubscribe/cleanupSession`，只有 `project()` 重载（@24/@34）做窗口裁剪映射为 `CandleDataPayload`，是纯投影 helper（调用方 `CandleDataProvider.kt:209`/`PublicShareRoutes.kt:145`/`InternalCliRoute.kt:527`）。

- 真正的 4 个订阅服务（均有三件套）：`CandleSubscriptionService`(148 行)、`IntradaySnapshotSubscriptionService`(118 行)、`StrategyPositionSubscriptionService`(129 行)、`StrategyPositionTrackingSubscriptionService`(259 行)。
- `AppWebSocketConnectionManager.kt:177-180` 依次 dispatch 这 4 个的 `cleanupSession`。
- **service-first 三服务样板逐字重复**（`buffer(Channel.CONFLATED).collectLatest` 各命中 1 处：Intraday@80、Positions@87、PositionTracking@120）：
  - `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`
  - `subscriptionJobs = ConcurrentHashMap<session, Job>`
  - `StrategySnapshotCursor` + `cursor.shouldAccept(snapshot)` 去重
  - `subscriptionJobs.remove(session)?.cancel()` 重订阅互斥
  - `cleanupSession → unsubscribe` 一行转发
  - `sentInitial` 首帧 SYNC 后续 UPDATE
  - `shutdown(){ scope.cancel() }`
- `CandleSubscriptionService` 是另一形态（构造注入 `CandleDataProvider`，三件套全委派本地 provider，无 scope/cursor，走本地推送非 remote flow），**合理地不与那三者同基类**。
- **代价**：每新增一个 service-owned topic 都要复制全套样板；可抽 `ServiceOwnedTopicSubscriptionService(topic, currentRemote, observeRemote, encodePayload)` 基类收口。

### P8【med】`shared` 有垃圾桶倾向（命名空间分裂）

- commonMain 47 文件 6850 行，命名分裂：**34 个文件用裸包**（`model.*`、`util`、`utils`），13 个文件用 `org.shiroumi.*`。
- 并存 **`util`（`DateTimeUtils.kt`）与 `utils`（`Utils.kt`/`Log.kt`）两个职责重叠的双包**。
- **代价**：无统一归置策略，各功能往 shared 直接塞模型；与 `server.share` 等命名叠加放大歧义。

### P9【med】`tool` / `tools` 同名包 + `share` / `shared` 混淆

- `server/tool/`（`package org.shiroumi.server.tool`）：`VerifyRepair.kt`、`RepairPingAnData.kt` 两个 `fun main` 数据修复脚本。
- `server/tools/`（`package org.shiroumi.server.tools`）：`BandwagonEffectPicker.kt` 一个 `object` 选股工具。
- 两包名仅末尾 `s` 之差，职责均为辅助工具却分裂成两个包根。
- `server/share/`（6 文件，匿名分享页 HTML 渲染）与顶层 KMP 模块 `shared` 仅差单复数，语义完全无关，在 import/搜索/对话中持续制造歧义。

### P10【med】`runtime` 沦为启动期/运行期杂活聚集地

- `server/runtime/` 25 文件 = 5 个子包 22 文件 + 3 个散文件，职责跨度极大：
  - `lifecycle`(4)：启动门禁（`ServerLifecycleManager`/`LifecycleGate`/...）
  - `market`(2)：市场状态投影
  - `stock`(2)：目录快照 + 报价转 WS
  - `strategy`(3)：持仓缓存 + 跨服务 adapter（`StrategyRuntimeBridge`）
  - `update`(11 文件 1873 行)：历史回补/补偿/15m/5m/涨跌停同步
- 内聚靠「都在运行时跑」这种弱关联。其中 `runtime/update` 与单文件包 `server/service/DataUpdateService` 双头管理「数据更新」：`DataUpdateService`（220 行）是 object 形态的**状态门面**（自持 `StateFlow`、60 秒心跳、持久化、show/hide 决策、WS 广播），把调度编排委托给 `runtime.update`（import L13-15）。
- **代价**：`lifecycle`（应属 infra）与 `update`（应属数据层）被错误归为一类；`update` 数据同步与 `service` 门面双头管理。

---

## 4. 整改路线图（按性价比排序）

| # | 动作 | 涉及 | 工作量 | 风险 | 收益 |
|---|---|---|---|---|---|
| 1 | 删 `ktor-server → core` 死边（`build.gradle.kts:36` 一行） | P6 | 极小 | 无（0 引用） | 收窄耦合面到 client 单一入口 |
| 2 | **决断 `dataprovider` 空框架**：要么补齐第一个 Provider 完成迁移并删 `data/*`；要么明确放弃、删 `dataprovider/{contract,port,registry,provider,store}` | P1 | 中（12 文件 + Bootstrap 装配） | 低（空 registry 删了无行为变化） | 消除双数据层认知双义 + 启动空转 |
| 3 | core 的 7 个符号下沉到 `shared`，解 `database→core` 倒置。**前置**：先把 `MarketSentimentSnapshot`/`StockFactorSnapshot` 从 Calculator 文件拆出、把 `SentimentDerivedFields`/`restoreSentimentDerivedFields`/`SentimentMath` 拆离计算公式 | P5、P8 | 中（拆 DTO + 包移动 + import 批改） | 低（无环新增、无行为改动） | 删倒置边 + 给 shared 收口位 |
| 4 | 抽 `ServiceOwnedTopicSubscriptionService` 基类，收 3 个 service-first 订阅服务样板（`PositionTracking` 的 follow-start-date 校准作子类扩展点） | P7 | 低-中（3 文件 + 1 基类） | 低（纯重构） | 降未来新 topic 样板成本 |
| 5 | 路由统一到 `org.shiroumi.server.route` 下按领域分子包；合并 `tool`/`tools`；统一 shared 的 `util`/`utils` 与根包命名 | P4、P8、P9 | 中（包移动 + import 批改） | 低 | ktor-server 内聚度提升 |
| 6 | 14 个 ops `main` 移出主源码树（独立 `data-ops` 模块或 `cli` 子命令） | P3 | 中 | 中（需对齐 Gradle run task 与 deploy 脚本） | 服务进程 classpath 瘦身、构建解耦 |
| 7 | （战略）把 `runtime`/数据供给下沉为独立 `data-runtime` 模块，被 backtest/research 复用；`lifecycle` 上提 infra，`update` 归数据层 | P2、P10 | 大 | 中-高 | 拆掉 17446 行杂物袋 |

**执行顺序建议**：1 → 2 → 3（零/低风险先行，立即收窄耦合面与消除迁移残骸）→ 4/5（重构内聚）→ 6/7（结构性拆模块，需单独立项）。

---

## 5. 维护约定

- 本文档是后端架构**深度扫描 + 整改路线图**的 SSOT，落地任一整改项后回头更新对应小节的状态。
- 涉及具体模块链路的修改，仍以对应 skill（`deployment-architect`/`database-runtime-architect`/`socket-protocol`/`sentiment-selection-architect`/`backtest-engine-architect`/`research-pipeline-architect`）的 reference 文档为准；本文档只承载跨模块的架构耦合判断。
- 文中所有行号/计数为 2026-06 扫描时的事实快照，代码演进后以当前代码为准重新核验。

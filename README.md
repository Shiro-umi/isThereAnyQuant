# Quant — A-Share Quantitative Trading System

`quant` 是一个面向 A 股市场的全栈量化交易系统，采用 **Kotlin Multiplatform (KMP)** 技术栈构建，覆盖数据获取、策略研究、回测验证、实盘信号生产到 Web/Android 双端呈现的完整链路。

---

## 目录

- [技术栈概览](#技术栈概览)
- [系统全景图](#系统全景图)
- [模块分层架构](#模块分层架构)
- [模块架构详解](#模块架构详解)
  - [前端层：compose-app](#前端层-compose-app)
  - [后端层：ktor-server](#后端层-ktor-server)
  - [策略层：strategy-server](#策略层-strategy-server)
  - [回测层：backtest](#回测层-backtest)
  - [数据层：database / network](#数据层-database--network)
  - [共享层：shared](#共享层-shared)
  - [AI Agent 层：agent](#ai-agent-层-agent)
  - [工具层：cli / tools](#工具层-cli--tools)
  - [代码生成层：ksp](#代码生成层-ksp)
- [核心数据流](#核心数据流)
- [架构设计亮点](#架构设计亮点)
- [快速开始](#快速开始)
- [部署模式](#部署模式)

---

## 技术栈概览

```mermaid
flowchart LR
    subgraph Frontend["前端"]
        F1["Compose<br/>Multiplatform"]
        F2["Material3<br/>Adaptive"]
        F3["Kamel<br/>Image"]
        F4["WasmJS +<br/>Android"]
    end
    subgraph Backend["后端"]
        B1["Ktor 3.x"]
        B2["Kotlin<br/>Coroutines"]
        B3["JWT +<br/>BCrypt"]
        B4["WebSocket"]
    end
    subgraph Strategy["策略"]
        S1["Kotlin JVM"]
        S2["ACP SDK"]
    end
    subgraph Data["数据"]
        D1["Exposed ORM"]
        D2["HikariCP"]
        D3["Ktor Client"]
        D4["H2 / JDBC"]
    end
    subgraph Build["构建"]
        G1["Gradle + Kotlin<br/>DSL + KSP"]
        G2["Kotlin 2.x<br/>JVM 17"]
    end
```

---

## 系统全景图

```mermaid
flowchart TB
    DS["数据源头<br/>Tushare / 券商 API"] --> DA["数据获取与预处理层"]

    subgraph DA["数据获取与预处理层"]
        GC["get-candles<br/>日K/分钟K"]
        ME["market-emotion<br/>市场情绪"]
        GR["get-reports<br/>券商研报"]
        GLL["get-limit-list<br/>涨跌停数据"]
        DB1[("database<br/>Exposed ORM")]
        GC & ME & GR & GLL --> DB1
    end

    DB1 --> SC["策略计算层<br/>独立进程"]

    subgraph SC["策略计算层"]
        subgraph service["strategy-server:service"]
            CP["盘后确认<br/>700日历史 / 21步算法<br/>确认结果落库"]
            DP["盘中投影<br/>盘后seed + 实时DAY<br/>内存计算"]
            SN["Snapshot 发布<br/>INTRADAY / POSITIONS<br/>POSITION_TRACKING / HEALTH"]
            CP --> DP --> SN
        end
        subgraph core["strategy-server:core"]
            PB["preparedBars"] --> SE["sentiment"]
            SE --> SL["selection"]
            SL --> TR["trade"]
            TR --> SZ["sizing"]
            SZ --> PL["plan"]
        end
    end

    SC --> CO["strategy-server:contract<br/>Snapshot / Command / Topic"]

    CO -->|"socket / JSONL"| KT["后端适配层"]

    subgraph KT["ktor-server 后端适配层"]
        REST["REST API<br/>/api/auth<br/>/api/backtest<br/>/api/market"]
        WS2["WebSocket<br/>前端推送<br/>INTRADAY<br/>POSITIONS"]
        SAC["策略 Snapshot 适配<br/>contract client<br/>订阅→回放 / 广播→实时"]
        WEB["Web 静态资源<br/>compose-app WasmJS"]
    end

    KT --> WEBF["Web 前端<br/>WasmJS + KMP"]
    KT --> AND["Android App<br/>Compose + KMP"]
```

---

## 模块分层架构

```mermaid
flowchart TB
    Pres["Presentation 前端层<br/>compose-app（Web + Android）"] --> Backend
    Backend["Backend 后端层<br/>ktor-server<br/>REST · WS · Auth · 静态资源托管"] --> Strategy
    Strategy["Strategy 策略层<br/>contract / core / client / service（独立进程）"] --> Backtest
    Backtest["Backtest 回测层<br/>A股规则引擎 · 纯执行层 · 零账户感知"] --> Data & Shared & Agent
    Data["Data 数据层<br/>database Exposed / network Ktor Client"] --> Tooling
    Shared["Shared 共享层<br/>KMP commonMain / JVM / WasmJS<br/>编译期配置注入"]
    Agent["Agent AI层<br/>ACP SDK / 交互式Agent"]
    Tooling["Tooling 工具层<br/>cli 统一入口 / tools 数据CLI / ksp 代码生成"]
```

---

## 模块架构详解

### 前端层：compose-app

```mermaid
flowchart TB
    subgraph compose["compose-app KMP"]
        subgraph cm["commonMain"]
            CUI["Compose UI<br/>Material3<br/>Adaptive Nav<br/>ViewModel<br/>Skill Presets（编译期生成）"]
        end
        subgraph wm["wasmJsMain"]
            KCJS["Ktor Client JS<br/>Browser DOM"]
        end
        subgraph am["androidMain"]
            KCCIO["Ktor Client CIO<br/>Splash Screen<br/>DataStore"]
        end
        CUI --> KCJS
        CUI --> KCCIO
    end
    CUI -.->|"资源同步"| RES["shared/resources/app-icons<br/>→ Compose / Web / Android"]
```

**核心设计**：
- **Material3 Adaptive 布局**：响应式导航套件，自适应手机/平板/桌面
- **跨平台 SVG 资源同步**：`syncCrossPlatformSvgAssets` Task 将 `shared/src/commonMain/resources/app-icons` 统一分发到三端
- **编译期 Skill Presets 生成**：扫描技能 metadata，自动生成前端技能列表代码
- **图片加载**：Kamel 跨平台图片库，支持 SVG 解码

**依赖**：`:shared`

---

### 后端层：ktor-server

```mermaid
flowchart TB
    subgraph ktor["ktor-server"]
        subgraph route["请求路由层"]
            A1["/api/auth<br/>JWT签发"]
            A2["/api/backtest<br/>test/run"]
            A3["/api/market<br/>sentiment"]
            A4["/api/agent<br/>skills"]
        end
        
        subgraph adapt["策略适配层 strategy-server:client"]
            SSC["SocketStrategyRuntimeClient<br/>→ subscribe snapshot"]
            SNAP["INTRADAY_SNAPSHOT / STRATEGY_POSITIONS<br/>→ WebSocket 推送给前端"]
            SSC --> SNAP
        end
        
        subgraph web["Web 静态资源（自动集成）"]
            CW["copyWebApp → build/generated/resources/webapp/static/"]
            IDX["index.html + wasm + sw.js<br/>（缓存版本号自动注入）"]
            CW --> IDX
        end
        
        route --> adapt
    end
```

**核心设计**：
- **前端产物一体化部署**：`compose-app` WasmJS 产物自动复制到 Ktor 静态资源，缓存版本号自动注入
- **多模式运行时**：`debug` / `debug-wan` / `release` 三模式，通过 `quant.mode` 切换
- **Shadow Jar 部署包**：合并 SPI 服务文件，输出带目录结构的完整部署包
- **策略服务适配器**：Ktor 仅消费策略 Snapshot，不持有任何策略计算逻辑

**依赖**：`:network` `:database` `:backtest` `:shared` `:strategy-server:client` `:strategy-server:core` `:agent`

---

### 策略层：strategy-server

#### 五层架构

```mermaid
flowchart TB
    subgraph SS["strategy-server"]
        CT["contract<br/>Snapshot · Command<br/>Topic · Ack/Error"]
        CL["client<br/>Ktor 侧适配<br/>订阅 / 发送"]
        CR["core<br/>情绪计算 · 因子打分<br/>组合选择 · 持仓推演"]
        TS["testing<br/>策略回放<br/>契约 Fake · 验证工具"]
        SV["service（独立 JVM 进程）<br/>盘后确认 · 盘中投影<br/>socket 监听 · snapshot 广播<br/>command 处理 · 确认结果落库"]
    end
    CL --> CT
    CR --> CT
    CT --> SV
    CL --> SV
    CR --> SV
```

#### 盘后链路 vs 盘中链路

**盘后确认链路（每日收盘后执行）**

```mermaid
flowchart LR
    OH["日线事实<br/>OHLCV"] --> NORM["价格口径标准化<br/>HFQ / RAW"]
    NORM --> WIN["700 日窗口<br/>滚动状态准备"]
    WIN --> ALGO["21 步算法链<br/>情绪 / 因子 / 组合"]
    ALGO --> DB["确认结果落库<br/>daily_target_portfolio"]
    DB --> PUB["发布 POSITIONS snapshot"]
```

**盘中投影链路（交易时段实时刷新）**

```mermaid
flowchart LR
    SEED["盘后 seed +<br/>实时 DAY facts"] --> PB["PreparedBar Factory"]
    PB --> SE["情绪实时计算<br/>strict 模式"]
    SE --> PORT["组合实时生成<br/>intraday"]
    PORT --> PUB2["发布 INTRADAY snapshot<br/>（不写 daily_* 确认表）"]
```

#### 四层策略计算体系

```mermaid
flowchart TB
    L1["Layer 1 · 市场情绪<br/>输入: preparedBars<br/>输出: sentimentExposure"]
    L2["Layer 2 · 选股<br/>输入: sentiment snapshot + factors<br/>输出: TOP-N candidates"]
    L3["Layer 3 · 买卖点<br/>输入: candidates / positions / quotes<br/>输出: BUY / SELL / HOLD / WAIT"]
    L4["Layer 4 · 仓位<br/>输入: target weights<br/>输出: sizing plan"]
    CHECK["A 股交易可行性检查<br/>T+1 / 整手 / 涨跌停"]
    PLAN["交易计划"]

    L1 --> L2 --> L3 --> L4 --> CHECK --> PLAN
```

**边界约束**：

```mermaid
flowchart TB
    KS["ktor-server"] --> CL["client"]
    CL --> CT["contract"]
    CR["core"] --> CT
    CR --> SV["service（独立进程）"]
    CT --> SV
```

- `core` 允许依赖：`shared`（领域模型）
- `core` 严禁依赖：`database` / `network` / `ktor` / `service runtime`（策略内核必须保持纯算法，零 IO，零账户感知）
- `backtest` 允许依赖：`shared` / `database` / `contract`
- `backtest` 严禁依赖：`core` / `service` / `runtime`（回测是执行层，策略是决策层，不可反向耦合）

---

### 回测层：backtest

#### 核心设计：策略零账户感知

**传统耦合架构（❌）**

```mermaid
flowchart LR
    S["策略"] --> BAL["查账户余额"]
    BAL --> CALC["算能买多少股"]
    CALC --> ORD["输出下单指令"]
    S -.- NOTE["策略结果依赖账户状态<br/>无法复现 · 无法跨账户比较<br/>同一策略跑 100 万 / 1000 万结果不同"]
```

**解耦架构（✅）**

```mermaid
flowchart TB
    S["策略<br/>不知道：账户余额 / 持仓数量<br/>T+1 锁仓 / 上次成交"] --> VIEW["输出「市场观点」<br/>目标组合权重 Map&lt;Code, Weight&gt;<br/>或方向意图 BUY / SELL / HOLD"]
    VIEW --> ENG["回测引擎（纯执行层）<br/>权重→金额→股数<br/>100 股取整 · A 股规则检查<br/>模拟撮合"]
    ENG --> OUT["模拟成交结果<br/>权益曲线 · 绩效报告"]
    S -.- RULE["同一策略 → 任何初始资金 → 相同权重序列"]
```

#### A 股规则引擎

```mermaid
flowchart TB
    IN["输入<br/>策略意图（权重 / 方向）+<br/>市场数据（OHLCV / 停牌 / 日历）"]
    subgraph PIPE["规则检查流水线"]
        R1["交易日判定<br/>TradingCalendar / TradingSession"]
        R2["可交易性判定<br/>Tradability / Liquidity"]
        R3["数量检查<br/>LotSize / OrderSizer"]
        R4["价格检查<br/>TickSize / PriceLimit"]
        R5["T+1 结算<br/>T1Settlement"]
        R6["1 买 1 卖语义<br/>PositionLedger"]
        R7["撮合引擎<br/>MatchingEngine"]
        R1 --> R2 --> R3 --> R4 --> R5 --> R6 --> R7
    end
    OUT["SimulationResult<br/>orders / positions<br/>cashFlows / equityCurve<br/>tradeAudit / metrics"]

    IN --> PIPE --> OUT
```

**边界约束**：

- `backtest` 允许依赖：
  - `:shared` — Candle / PriceBasis 等领域基础模型
  - `:database` — 行情、日历、停牌等市场数据读取
  - `:strategy-server:contract` — 策略输出契约适配
- `backtest` 严禁依赖：
  - `:strategy-server:core` / `:service`
  - 原因：策略层不允许感知账户私有域；反向耦合将破坏「策略零账户感知」边界

---

### 数据层：database / network

```mermaid
flowchart LR
    subgraph DB["database"]
        DB1["JetBrains Exposed ORM<br/>Schema / Table<br/>Repository<br/>Bootstrap · Migration"]
        DB2["HikariCP 连接池<br/>H2（测试 / 本地）<br/>JDBC（生产）<br/>动态表名 ``ts_code``"]
    end
    subgraph NET["network"]
        N1["Ktor Client Core<br/>CIO Engine（backend）<br/>JS Engine（frontend）"]
        N2["ContentNegotiation<br/>kotlinx.serialization<br/>Guava 缓存 / 集合工具"]
    end
```

职责收束：`database` 只保留持久化职责；策略计算已迁至 `:strategy-server:core`。

---

### 共享层：shared

```mermaid
flowchart TB
    subgraph CM["commonMain"]
        DTO["DTO Models"]
        DOM["Domain Primitives"]
        SER["Serialization<br/>kotlinx.json"]
    end
    JVM["jvmMain<br/>backend / agent<br/>YAML 解析（kaml）"]
    WASM["wasmJsMain<br/>Web 前端<br/>浏览器适配"]
    GEN["编译期生成<br/>AppEnvironment<br/>AgentModelPresetCatalog"]

    CM --> JVM
    CM --> WASM
    CM --> GEN
```

**generateAppEnvironment（编译期 Task）**

```mermaid
flowchart LR
    YAML["config.yaml"] --> PARSE["解析 deployment.modes.*"]
    PARSE --> ENV["生成 AppEnvironment.kt<br/>BASE_URL / WS_BASE_URL / PORT<br/>编译期常量"]
```

设计目标：前端零运行时配置解析，API 地址编译期锁定，安全无泄露。

---

### AI Agent 层：agent

```mermaid
flowchart TB
    ACP["ACP（Agent Client Protocol）<br/>com.agentclientprotocol:acp"]
    LAUNCH["AgentLauncher<br/>独立 JVM 应用入口"]
    CLI["InteractiveTest<br/>交互式命令行启动器<br/>./agent.sh"]

    ACP --> LAUNCH --> CLI
```

---

### 工具层：cli / tools

```mermaid
flowchart TB
    CLI["cli（应用入口）<br/>./cli [command] [options]"]
    subgraph TOOLS["tools 模块"]
        T1["get-candles<br/>日 K 数据"]
        T2["get-intraday<br/>分钟 K"]
        T3["get-research<br/>券商研报"]
        T4["get-industry<br/>行业研报"]
        T5["market-emotion<br/>市场情绪"]
        T6["get-limit-list<br/>涨跌停数据"]
    end
    CLI --> TOOLS
```

`cli` 同时装配 backtest 引擎 + database 直连，支持本地隔离模式回测。

---

## 核心数据流

### 端到端交易闭环

```mermaid
flowchart LR
    A["数据获取<br/>tools/* · database<br/>原始数据"]
    B["策略计算<br/>strategy-server:core<br/>算法 / 回测"]
    C["回测验证<br/>backtest:engine<br/>A 股规则"]
    D["信号生产<br/>strategy-server:service<br/>盘中 snapshot"]
    E["前端呈现<br/>compose-app<br/>Web / Android · 实时推送"]
    A --> B --> C --> D --> E
```

### 策略服务与 Ktor 的交互

```mermaid
flowchart LR
    subgraph SVC["strategy-service（独立 JVM 进程）"]
        TOPIC["INTRADAY · POSITIONS<br/>POSITION_TRACKING · HEALTH"]
        CMD["command 处理<br/>RebuildDate · RefreshIntraday"]
    end
    subgraph KS["ktor-server（HTTP + WebSocket）"]
        WSA["前端 WS 适配层<br/>topic 转换"]
        CLIENT["Web 前端 WS<br/>Android WS"]
    end
    SOCK["socket（本地 / 远程）<br/>JSONL frame · snapshot / command"]

    TOPIC -- 发布 snapshot --> SOCK
    SOCK -- subscribe / publish --> WSA
    WSA --> CLIENT
    WSA -- command --> SOCK
    SOCK --> CMD
```

---

## 架构设计亮点

### 1. Kotlin Multiplatform 全栈统一

```mermaid
flowchart TB
    CM["commonMain（共享业务逻辑）<br/>DTO / 序列化 / 领域模型"]
    CM --> JVM["jvmMain"] --> BE["backend<br/>Ktor"]
    CM --> WASM["wasmJsMain"] --> WEB["Web 前端<br/>浏览器"]
    CM --> AND["androidMain"] --> APP["Android App"]
```

- **一门语言贯穿全链路**：前端、后端、策略内核全部 Kotlin
- **shared 消除 DTO 重复**：前后端共用同一套序列化模型
- **expect/actual 机制**：网络、存储、时间等能力跨平台统一抽象

### 2. 策略服务独立化

**迭代前（耦合）**

```mermaid
flowchart TB
    K1["Ktor<br/>策略计算 + API"] --> DOWN["修改策略需重启整个后端<br/>全站宕机"]
```

**迭代后（解耦）**

```mermaid
flowchart LR
    K2["Ktor（adapter）"] <--> SVC["strategy-service<br/>独立进程"]
    K2 --> USER["前端用户<br/>零感知升级"]
    SVC -.- NOTE["Agent 修改策略：只需重启 service<br/>Ktor 不中断"]
```

- **内核与运行时分离**：`core` 纯算法库，多场景复用
- **进程级隔离**：Agent 迭代策略后只需重启 service
- **契约驱动通信**：`contract` 定义协议，实现可替换
- **权责唯一**：四大 topic 唯一 owner，杜绝双写冲突

### 3. 回测引擎「策略零账户感知」

```mermaid
flowchart TB
    S["策略输出 → 市场观点（权重 / 方向）<br/>策略不知道：账户余额 / 持仓数量 /<br/>T+1 锁仓 / 上次成交结果"]
    E["回测引擎 → 内化模拟账户 → A 股规则检查 → 模拟撮合<br/>回测知道：可用现金 / 总权益 ·<br/>可用持仓 / 冻结持仓 ·<br/>整手取整 / T+1 结算 ·<br/>涨跌停 / 停牌 / 流动性"]
    R["执行结果<br/>权益曲线 · 绩效报告"]
    RULE["同一策略 → 任何初始资金 → 相同权重序列"]

    S --> E --> R
    R -.- RULE
```

- **策略可复现**：不受账户状态污染
- **回测↔实盘对齐**：替换 `AccountLedger` 即可接入实盘
- **多账户并行**：同一策略同时跑多档资金规模

### 4. A 股交易规则精确建模

```mermaid
flowchart TB
    IN["策略意图"]
    subgraph PIPE["规则检查流水线（每条规则 = 独立可测类）"]
        R1["TradingCalendar<br/>是否交易日？"]
        R2["TradabilityRule<br/>是否停牌 / 退市？"]
        R3["PriceLimitRule<br/>是否涨停 / 跌停？"]
        R4["LotSizeRule<br/>买入 100 股整数倍？"]
        R5["T1SettlementRule<br/>可卖数量 ≥ 卖出量？"]
        R6["LiquidityRule<br/>成交量足够？"]
        R7["PositionLedger<br/>1 买 1 卖语义检查"]
        R8["MatchingEngine<br/>撮合成交"]
        R1 --> R2 --> R3 --> R4 --> R5 --> R6 --> R7 --> R8
    end
    IN --> PIPE
```

### 5. 编译期配置安全注入

```mermaid
flowchart TB
    YAML["config.yaml<br/>唯一真理源"]
    TASK["generateAppEnvironment<br/>Gradle Task（编译期）<br/>读取 deployment.modes.*<br/>生成 AppEnvironment.kt"]
    OBJ["object AppEnvironment {<br/>const val API_BASE_URL = ...<br/>const val WS_BASE_URL = ...<br/>const val PORT = ...<br/>}<br/>编译期常量 · 运行时零解析"]

    YAML --> TASK --> OBJ
```

- 前端不携带运行时配置解析逻辑
- API / WebSocket 地址编译期锁定
- 模型预设零 API Key 泄露

### 6. 跨平台 SVG 资源统一管理

```mermaid
flowchart TB
    SRC["shared/src/commonMain/resources/app-icons/<br/>brand_mark.svg<br/>web/icon.svg<br/>android/ic_launcher_*.xml<br/>android/ic_splash_logo.xml"]
    TASK["syncCrossPlatformSvgAssets<br/>Gradle Task"]
    OUT1["composeResources/drawable/"]
    OUT2["wasmJsMain/resources/"]
    OUT3["androidMain/res/drawable/"]

    SRC --> TASK
    TASK --> OUT1
    TASK --> OUT2
    TASK --> OUT3
```

- 单一数据源，三端同步
- 修改一处，全局生效

### 7. 多模式部署与版本管理

**部署模式矩阵**

| 模式 | 用途 | 前端产物 | 配置源 |
|------|------|----------|--------|
| debug | 本地开发 | development | `modes.debug` |
| debug-wan | 局域网调试 | development | `modes.debug-wan` |
| release | 生产部署 | production | `modes.release` |

切换方式：`-Pquant.mode=xxx` 或 `QUANT_MODE=xxx`。

**部署包结构**

```mermaid
flowchart TB
    ROOT["quant-server-x.x.x-mode/"]
    ROOT --> BIN["bin/<br/>启动脚本 .sh / .bat"]
    ROOT --> LIB["lib/<br/>fat JAR（shadowJar）"]
    ROOT --> CFG["config/<br/>YAML 配置"]
    ROOT --> LOG["logs/<br/>运行时日志"]
    ROOT --> DAT["data/<br/>持久化数据"]
    ROOT --> SS["strategy-service/<br/>独立策略服务"]
```

打包命令：

```bash
./gradlew :ktor-server:packageDebug
./gradlew :ktor-server:packageRelease
```

---

## 快速开始

### 环境要求

- JDK 17+
- Gradle 8.x（wrapper 已包含）
- Node.js（前端 Web 构建）

### 构建命令

```bash
# 全量构建
./gradlew build

# 后端服务器
./gradlew :ktor-server:run

# Web 前端开发服务器
./gradlew :compose-app:wasmJsBrowserDevelopmentRun

# Android 安装
./gradlew :compose-app:installDebug

# 策略服务独立运行
./gradlew :strategy-server:service:run

# CLI 工具
./gradlew :cli:installDist
./cli get-candles --code 000001.SZ
```

### 配置

- 复制 `config.example.yaml` 为 `config.yaml`，填入私有配置
- `shared` 模块编译期自动读取 `config.yaml` 生成 `AppEnvironment`
- `compose-app` 的 `keystore.properties` 用于 Android release 签名（可选）

---

## 部署模式

| 模式 | Gradle 属性 | 用途 | 前端产物 | 端口来源 |
|------|-------------|------|----------|----------|
| debug | `-Pquant.mode=debug` | 本地开发 | developmentExecutable | `config.yaml` 中 `deployment.modes.debug` |
| debug-wan | `-Pquant.mode=debug-wan` | 局域网调试 | developmentExecutable | `config.yaml` 中 `deployment.modes.debug-wan` |
| release | `-Pquant.mode=release` | 生产部署 | productionExecutable | `config.yaml` 中 `deployment.modes.release` |

```bash
# 打包 debug 部署包
./gradlew :ktor-server:packageDebug -Pquant.mode=debug

# 打包 release 部署包（含 Android APK）
./gradlew :ktor-server:packageRelease -Pquant.mode=release
```

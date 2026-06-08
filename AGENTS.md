# Quant Project - AI Agent Skill Sets & Guidelines

## Project Overview
`quant` is a full-stack quantitative trading system built with Kotlin Multiplatform (KMP).
- **Backend**: Ktor-based server (`ktor-server`).
- **Frontend**: Compose Multiplatform (`compose-app`), primarily targeting Web (JS/Wasm).
- **Trading Logic**: Core business logic and backtesting (`trading`).
- **Database**: JetBrains Exposed ORM (`database`).

## Development Commands

## Project-local Skills
- 项目本地 Skill 位于 `.claude/skills/<skill-name>/SKILL.md`。Claude Code 会原生发现；Codex 也必须按本节主动读取并遵守同一份 Skill，避免两套规则分叉。
- 公开仓库不跟踪 `.claude/skills`；本地开发通过 `private/claude-skills` 软链装配。缺少私有 skill 时，按 `AGENTS.md` 与公开文档执行。
- Skill 是行为准则，不是 shell 命令，也不是自定义工具协议；不要发明 skill 调用别名。
- 当用户意图、debug、review 或修改触发某个项目 Skill 时，先读取对应 `SKILL.md`，再按 Skill 要求读取 reference 文档。
- **回测引擎强制触发**：只要涉及 `backtest` 模块、回测 CLI/HTTP 入口、`BacktestRunExecutor`、`BacktestScheduler`、`StrategyDecisionFeed`、`DbBackedDecisionFeed`、`DecisionFileExporter`、`FileBackedDecisionFeed`、`BacktestMarketDataFeed`、`OrderSizer`、`RuleValidator`、`MatchingEngine`、`AccountLedger`、`PerformanceReporter`、`.backtest/` 工作区、`/api/backtest/run`、回测结果输出、回测结果是否落库或 `docs/architecture/backtest-engine-design.md`，必须读取 `.claude/skills/backtest-engine-architect/SKILL.md`。对回测业务链路、模块边界、结果输出、数据库读写语义或 HTTP/CLI 合同做修改时，必须同步检查并维护回测架构文档；若无需更新，最终输出说明依据。
- **KMP Compose 前端工程强制触发**：只要涉及 Compose Multiplatform/Kotlin Multiplatform 前端工程、`compose-app`、前端消费的 `shared` 模型/配置、source set、expect/actual、Web(JS)/Android/Desktop 平台入口、AuthGate、Navigation 根布局、AppTheme、前端 ViewModel 生命周期、HTTP/WebSocket 客户端、Repository 到 UI 数据流、Compose/PWA/Android 资源、`compose-app/build.gradle.kts`、`shared/build.gradle.kts` 中前端配置或 `AppEnvironment`，必须读取 `.claude/skills/kmp-compose-frontend-architect/SKILL.md`。对该维度代码进行改动时，必须同步检查并维护 `.claude/skills/kmp-compose-frontend-architect/references/kmp-compose-frontend-architecture.md`；若无需更新，最终输出说明依据。
- **部署相关强制触发**：只要涉及部署、发布、debug/release 模式、`deploy.sh`、Gradle deployment/distribution 任务、Ktor 启停脚本、部署包、Docker、端口 `9870/9871`、`QUANT_MODE`、运行时配置、日志/PID/data 目录、生产域名或本地调试地址，必须读取 `.claude/skills/deployment-architect/SKILL.md`。对部署功能和上下游做修改时，必须同步检查并维护 `.claude/skills/deployment-architect/references/deployment-architecture.md`；若无需更新，最终输出说明依据。
- **数据库运行时强制触发**：只要用户意图、debug、review、方案设计或代码修改涉及数据库表结构、Repository、Exposed 事务、schema/bootstrap/migration、连接池、server 启动/运行时数据库性能、历史更新、Provider warmup、订阅/路由读库链路，必须读取 `.claude/skills/database-runtime-architect/SKILL.md`。对数据库相关代码或上下游链路做修改时，必须同步检查并维护 `.claude/skills/database-runtime-architect/references/database-runtime-architecture.md`；若无需更新，最终输出说明依据。
- **情绪策略与选股强制触发**：只要用户意图、debug、review、方案设计、代码修改，或你的思考涉及市场情绪、日频/盘中策略因子、情绪仓位、目标组合、选股结果、策略持仓、策略审计、盘中 H/R/merged 投影、`INTRADAY_SNAPSHOT`、`STRATEGY_POSITIONS`、策略跟踪、`daily_stock_factor`、`daily_market_sentiment`、`daily_market_sentiment_state`、`sentiment_runtime_seed`、`daily_target_portfolio`、`daily_strategy_audit`，必须读取 `.claude/skills/sentiment-selection-architect/SKILL.md`。对这部分代码或上下游链路做修改时，必须同步检查并维护 `.claude/skills/sentiment-selection-architect/references/sentiment-selection-architecture.md` 与 `.claude/skills/sentiment-selection-architect/references/sentiment-selection-strategy-flow.md`；若无需更新，最终输出说明依据。
- **跨端 SVG 资源强制触发**：只要涉及 SVG、矢量图标、品牌标识、Web favicon/PWA 图标、Compose `composeResources` drawable、Android vector drawable、adaptive icon、Splash 图标、Kamel SVG、资源生成/分发 Gradle task，或从 `shared` 统一维护并分发到 KMP 各平台资源目录的链路，必须读取 `.claude/skills/cross-platform-svg-assets/SKILL.md`。对跨端 SVG 架构、代码、资源或生成链路做修改时，必须同步检查并维护 `.claude/skills/cross-platform-svg-assets/references/svg-assets-architecture.md`；若无需更新，最终输出说明依据。

## Architecture Documentation Discipline
- 任何开发或 review 涉及业务链路、模块边界、生命周期、缓存/订阅语义、数据流或架构约束变化时，必须主动检查相关架构文档与项目内 skill 文档是否需要同步更新；若不需要更新，也应在最终输出中说明判断依据。

## Data Acquisition In Agent Workspace
- K线数据不是 Skill，而是 agent 工作目录中的 CLI 命令能力
- 调用带股票代码参数的 CLI 时，统一使用带交易所后缀的 ts_code（如 `000001.SZ`、`600519.SH`）
- 日K数据：`./get-candles --code 000001.SZ`
- 小周期K线：`./get-intraday-candles --code 000001.SZ --period 30min`
- 涨跌停/炸板：`./get-limit-list --code 000001.SZ`
- 券商研报：`./get-research-reports --code 000001.SZ`
- 行业研报：`./get-industry-research-reports --ind-name 半导体`
- 市场情绪：`./market-emotion`
- 当分析类 Skill 需要行情或情绪参数时，应先调用上述 CLI 命令取数，再基于返回结果执行分析

## UI Motion Convention
- 所有非线性动画统一使用减速型动画（decelerate easing），要求高效、灵动、干净
- 禁止使用弹跳、回弹、闪烁感明显或节奏拖沓的动画
- 展开/折叠、浮现、内容尺寸变化等过渡，优先使用同一套减速曲线与短时长，保证交互一致性

### Gradle Build Tasks
- **Full Project**: `./gradlew build`
- **Agent Module**: `./gradlew :agent:classes`
- **Server Module**: `./gradlew :ktor-server:build`
- **Compose App**: `./gradlew :compose-app:build`

### Running the Application
- **AI Agent (Interactive)**: `./agent.sh`
- **Backend Server**: `./gradlew :ktor-server:run`
- **Web Frontend**: `./gradlew :compose-app:wasmJsBrowserDevelopmentRun`
- **CLI Tool**: `./cli [command]` (e.g., `./cli get-candles --code 000001.SZ`)

## Repository Hygiene — Paths That Must Not Be Tracked
以下路径属于本地构建/工具产物或个人配置，**禁止**进入 git 跟踪。新增构建/IDE/工具产物时，必须先把对应目录写入 `.gitignore`，再使用 `git add`：

- 构建产物与缓存：`build/`、`out/`、`bin/`、`.gradle`、`.gradle-local/`、`.gradle_home/`、`.gradle-home/`、`.kotlin/`
- IDE / 编辑器：`.idea/`（仅显式 `!` 放行的少量文件除外）、`*.iml`、`*.ipr`、`*.iws`、`.vscode/`、`.settings/`、`.classpath`、`.project`
- 运行时 / 部署产物：`deploy/local/quant-server/lib/*.jar`、`deploy/local/quant-server/logs/`、`*.log`、`*.log.*`、`*.jar`（仅放行 `gradle-wrapper.jar` 与模块 `build/libs/*.jar`）
- 配置与敏感数据：`/config.yaml`、`/.env.model`、`/local.properties`（保留 `config.example.yaml`）
- 资源目录例外规则：`assets/` 默认忽略，仅放行 `prompt_*` 与子目录占位；`ktor-server/src/main/resources/static` 不进仓库
- 系统/壳别名：`.DS_Store`、`~/`、`*/~/`

执行准则：
- 看到 `git status` 出现新的本地构建/工具目录（如 `.gradle-home/`、`.kotlin/`、`build/` 等）一律先在 `.gitignore` 加规则，**不要** `git add`
- 若发现这些路径**已经**被跟踪，使用 `git rm -r --cached <path>` 解除跟踪后再提交
- 提交前用 `git diff --cached --stat` 自检，不允许把上述类别的文件混进业务提交

## Dependency Upgrade Discipline — 禁止降级
- **绝对禁止**为了规避编译/构建问题而降级任何依赖版本，包括但不限于：第三方库、Compose Multiplatform、AndroidX、AGP、Gradle、Kotlin、JDK。
- 包括所有降级手段：修改 `libs.versions.toml` 版本号、`resolutionStrategy.force`、`strictly`、`constraints { ... }` 强制旧版、`exclude` 后回填旧版、`dependencySubstitution` 替换成低版本等。
- 出现版本/SDK/插件不兼容时，**只能向上对齐**：升级触发约束的工具链（compileSdk、AGP、Gradle、Kotlin、Compose 等），而不是把上层依赖往回拽。
- 升级前先讲清楚连锁影响（AGP ↔ Gradle ↔ Kotlin ↔ compileSdk ↔ JDK 的兼容矩阵），并征求用户确认；禁止默默改 catalog 或在 build 脚本里塞 force/strictly。
- 例外仅限：上游依赖发布说明明确标注"撤回 / 已知严重 bug"，且用户**显式同意**回退。该情况必须在提交信息和 PR 描述中说明原因与上游链接。

## Database Guidelines
- **Framework**: Use **JetBrains Exposed** for all persistence logic.
- **Dynamic Tables**: When querying stock data tables (named by `ts_code`), always use backtick quoting: ``SELECT * FROM `{ts_code}` ... ``.
- **Reference**: Consult `GEMINI.md` for connection strings and schema documentation.

# Quant Project - AI Agent Skill Sets & Guidelines

## Project Overview
`quant` is a full-stack quantitative trading system built with Kotlin Multiplatform (KMP).
- **Backend**: Ktor-based server (`ktor-server`).
- **Frontend**: Compose Multiplatform (`compose-app`), primarily targeting Web (JS/Wasm).
- **Trading Logic**: Core business logic and backtesting (`trading`).
- **Database**: JetBrains Exposed ORM (`database`).

## Project-local Claude Code Skills
- 项目本地 Skill 位于 `.claude/skills/<skill-name>/SKILL.md`，由 Claude Code 原生机制发现和触发。
- Skill 是行为准则，不是 shell 命令，也不是自定义工具协议；不要发明 skill 调用别名。
- 当场景触发某个 Skill 时，读取对应 `SKILL.md` 并遵守其流程。
- **回测引擎强制触发**：只要涉及 `backtest` 模块、回测 CLI/HTTP 入口、`BacktestRunExecutor`、`BacktestScheduler`、`StrategyDecisionFeed`、`DbBackedDecisionFeed`、`DecisionFileExporter`、`FileBackedDecisionFeed`、`BacktestMarketDataFeed`、`OrderSizer`、`RuleValidator`、`MatchingEngine`、`AccountLedger`、`PerformanceReporter`、`.backtest/` 工作区、`/api/backtest/run`、回测结果输出、回测结果是否落库或 `docs/architecture/backtest-engine-design.md`，必须读取 `.claude/skills/backtest-engine-architect/SKILL.md`。对回测业务链路、模块边界、结果输出、数据库读写语义或 HTTP/CLI 合同做修改时，必须同步检查并维护回测架构文档；若无需更新，最终输出说明依据。
- **研究层强制触发**：只要涉及 `:strategy-server:research` 模块、七段研究管线（Source / Transform / Input / Study / Output / Compare / Conclusion）、`ResearchContext`、`ResearchStage`、`ResearchPipeline`、`ResearchStudy`、`ResearchEvaluation`、`SentimentResonanceStudy`、`SentimentEvaluation`、`ResonanceEvaluator`、`ResonanceCardWriter`、研究工作区 `research/sentiment_factor/`、`{workspace}/tuner/{runId}/` 产物、参数自动调优（`BlackBoxOptimizer`、`NelderMeadOptimizer`、`HillClimbingOptimizer`、`SimulatedAnnealingOptimizer`）或 PyTorch 训练产物，必须读取 `.claude/skills/research-pipeline-architect/SKILL.md`。对 research 模块的 pipeline / study / eval / output / tuner 任意一段做修改时，必须同步检查并维护 `.claude/skills/research-pipeline-architect/references/research-pipeline-architecture.md`；若无需更新，最终输出说明依据。
- **部署相关强制触发**：只要涉及部署、发布、debug/release 模式、`deploy.sh`、Gradle deployment/distribution 任务、Ktor 启停脚本、部署包、Docker、端口 `9870/9871`、`QUANT_MODE`、运行时配置、日志/PID/data 目录、生产域名或本地调试地址，必须读取 `.claude/skills/deployment-architect/SKILL.md`。对部署功能和上下游做修改时，必须同步检查并维护 `.claude/skills/deployment-architect/references/deployment-architecture.md`；若无需更新，最终输出说明依据。
- 数学计算不是 Skill；不要查找 `evaluate-math-expressions`，也不要调用 `evaluateMathExpressions`。所有数学计算统一使用 shell 命令 `bc`。
- **Skill 链接生效强制规范**：`.claude/skills` 是目录级 symlink，指向 `private/claude-skills`，两侧 inode 同源。放在 `private/claude-skills/<name>/` 下的新 skill 自动在主工程被原生发现机制触发。新增 skill 默认放在 `private/claude-skills/` 下。新 skill 放在其他位置时，必须立即 symlink 到 `.claude/skills/<name>` 使其被原生发现机制触发。

## Development Commands

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

## Agent Report quant-kline Rules
- 报告中的 K 线图只能使用 fenced code block：```` ```quant-kline` + 标准 JSON + ``` ````。
- 涉及买卖点的小周期分析时，分别扫描 60min、30min、15min，必要时补充 5min；扫描目标不是判断当前已经有没有信号，而是以当前价格为起点，推演价格如果突破、回踩、下探到支撑或跌破关键位，会触发什么买点/卖点。
- 每个小周期可以形成一个或多个条件触发候选模式；每个候选模式都要给出触发条件、买点、卖点、止损、目标位、盈亏比和对应 `quant-kline` 配图。
- 分析涨停股、连板股、炸板后修复、涨停强度或封板质量时，必须调用 `./get-limit-list --code {ts_code}` 查询真实涨跌停/炸板数据，结合 `openTimes`、`limitType`、`firstTime`、`lastTime`、`fdAmount`、`limitAmount`、`upStat` 判断强弱。
- 小周期配图只渲染局部交易窗口：根据该周期候选模式选择不超过 60 根 K 线，设置 `maxCandles` <= 60，并用 `focusDate` 对准预计触发买点/卖点的 K 线；如果触发点尚未发生，使用最接近触发区间的当前/最近 K 线。
- 每一组小周期条件触发模式都必须写在对应周期自己的 `quant-kline` 中：`markers.price` 标注预计买卖点价格，`tradePlan` 标注 `entryPrice`、`stopLossPrice`、`targetPrice`、`riskRewardRatio`。
- 最终要综合比较所有小周期候选模式，选择盈亏比最高且结构质量可靠的主交易模式；如果盈亏比最高但结构弱，要说明取舍。
- 不要使用机械状态标签；用自然语言说明当前价格位置、各周期价格怎么变化才触发交易、哪组模式最优。
- 不要在 Markdown 中输出完整 K 线数组；前端会按 `tsCode`、`period`、`startDate`、`endDate` 拉取并渲染。

## Agent Report Custom Analysis Blocks
除 `quant-kline` K 线图外，分析报告中可使用以下自定义渲染块增强关键判断的视觉表达。使用方式与 `quant-kline` 相同：fenced code block + JSON。

### quant-limit-up：涨停分析块
最近 3 日有涨停板时输出。展示涨停强度 + 主力行为分析。支持 `sealQuality`（强封/弱封/炸板，影响颜色标签）和 `consecutiveCount`（连板数）。

### quant-volume-price：异常量价分析块
窗口中出现异常量价关系时输出。展示量价分析 + 主力行为解读。`anomalyType` 支持：放量上涨、缩量上涨、放量下跌、缩量下跌、天量、地量。

### quant-market-sentiment：市场情绪块
独立输出市场情绪总结，用自然语言描述各维度（避免使用变量名）。`dialecticalRelationship` 字段以特殊视觉容器（左侧渐变色条 + 独立背景）突出渲染市场情绪与个股情绪的辩证关系。

详细字段定义和示例参考 `.claude/skills/entry-exit-analysis/SKILL.md`。

## Final Report Language Rules
- 工具返回的字段名只允许作为内部分析依据，最终 Markdown 正文和所有自定义块的展示字符串都必须写成人能理解的自然语言。
- 禁止在最终报告中出现公式化参数名或程序员式字段名，包括但不限于：`sentimentExposure`、`bullRatio`、`marketVol`、`fftScore`、`accelZ`、`volZ`、`emptyReason`、`residualScore`、`limitType`、`openTimes`、`fdAmount`、`limitAmount`、`upStat`。
- 应改写为业务语义：市场参与意愿、看涨扩散度、整体波动水平、周期共振强弱、情绪变化速度、封板打开次数、封单规模、板上成交额、连板状态等。
- 可以保留必要数值，但必须配合自然语言解释。例如不要写“bullRatio 0.396 不足 40%”，应写“看涨扩散度不足四成，说明上涨没有得到足够多股票的共同支持”。

## UI Motion Convention
- 所有非线性动画统一使用减速型动画（decelerate easing），要求高效、灵动、干净
- 禁止使用弹跳、回弹、闪烁感明显或节奏拖沓的动画
- 展开/折叠、浮现、内容尺寸变化等过渡，优先使用同一套减速曲线与短时长，保证交互一致性

## Math Calculation Convention
- 涉及百分比、价格区间、风险收益比、止损位、仓位大小等数学计算时，统一通过 shell 命令 `bc` 计算
- 不要直接心算或凭感觉给出数值结果
- 计算时优先一次性组织完整表达式，明确小数精度，例如：`echo "scale=4; (10.5 - 9.8) / 9.8 * 100" | bc`

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

## Research 文档私有化 — 研究方式不开源（强制）
research 的**研究设计文档**（因子构造、loss 设计、调优公式、逐因子有效性结论、研究方法学等承载"我们怎么研究"的内容）属于核心研究资产，**禁止进入公开仓 `origin`**，必须统一放在 private submodule 的 `private/research-docs/` 下管理。

- **唯一归置目录**：`private/research-docs/`。三份研究 topic 的 HTML 设计文档（`volume-price-factor-formula.html` 因子挖掘 / `sentiment-next-day-formula.html` trend / `pivot-reversal-formula.html` reversal）以及逐因子有效性文档都在此。
- **公开仓零研究文档**：`temp/`（已 gitignore）只能放临时草稿；研究文档一旦成型立刻迁入 `private/research-docs/`，不得留在 `temp/`、`docs/` 或任何被 `origin` 跟踪的路径。
- **新研究文档默认写到 private**：需要新建研究设计文档时，直接创建在 `private/research-docs/`，不要先落公开仓再迁移。
- **研究→文档闭环**：以新文档作为研究指导（SSOT）；研究迭代完成后，必须回头更新对应的研究文档内容（结论、最优参数、有效性层面），保持文档与实现一致。
- **代码 vs 文档边界**：`:strategy-server:research` 模块的 Kotlin 代码（pipeline/study/eval/tuner 等工程骨架）仍在公开仓；私有化的只是"研究设计文档"。但**新研究方向的因子构造公式、命中率/调优结论**等敏感内容只写进 `private/research-docs/` 的文档，不写进公开仓代码注释或公开 docs。
- 边界细则与 private submodule 布局见 `docs/open-source-private-config.md`。

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

## Backtest 模块
- 模块根目录：`backtest/`，Gradle 路径 `:backtest`
- 设计文档：`docs/architecture/backtest-engine-design.md`
- **关键边界**：策略层零账户感知。`backtest` 严禁依赖 `:strategy-server:core` / `:runtime` / `:service`，仅允许依赖 `:shared`、`:database`、`:strategy-server:contract`
- **核心语义**：1 买 1 卖（同一标的持仓期间禁止加仓、禁止分批卖出）、T+1（当日买入次日方可卖出）、现金不足按比例缩放（audit 写 `CASH_SCALED`）
- 模块入口：`BacktestRunExecutor` 组装 `DbTradingCalendar`、`DatabaseBacktestMarketDataFeed`、`DbBackedDecisionFeed`、`BacktestScheduler` 并聚合结果；现阶段回测结果不落库。
- CLI 入口：`./cli backtest --start 2024-01-02 --end 2024-12-31 --decisions audit --capital 1000000 --equity-curve-csv ./out/equity_{run_id}.csv`
- HTTP 入口：`POST /api/backtest/run` 同步执行并返回 `runId`、metrics 与逐日权益曲线；现阶段不提供基于落库结果的轮询查询。
- 最小示例：先启动 Ktor server，再执行 `./cli backtest --start 2024-01-02 --end 2024-12-31 --decisions audit --capital 1000000`。`audit` 来源优先读取 `daily_target_portfolio`，缺失时才回退 `daily_strategy_audit` 的新增/剔除列表。

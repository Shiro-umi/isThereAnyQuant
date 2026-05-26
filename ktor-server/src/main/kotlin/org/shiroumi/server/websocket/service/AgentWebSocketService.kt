package org.shiroumi.server.websocket.service

import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import model.agent.AgentAnalysisContext
import model.agent.AgentPromptRequest
import model.agent.AgentAnalysisType
import model.ws.*
import org.shiroumi.agent.api.AgentBridge
import org.shiroumi.agent.impl.AgentBridgeImpl
import org.shiroumi.agent.state.ClaudeState
import org.shiroumi.agent.state.ClaudeUpdate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.agent.model.AgentAnalysisResultModel
import org.shiroumi.database.agent.repository.AgentAnalysisResultRepository
import org.shiroumi.server.agent.AgentModelConfigResolver
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent WebSocket 服务
 *
 * 职责：
 * 1. 管理 Agent 会话（创建/关闭）
 * 2. 订阅 AgentBridgeImpl.observeState() 流，将状态变化实时推送给前端
 * 3. 转发前端的 Prompt / 权限批准 / 拒绝 命令
 */
object AgentWebSocketService {

    private val logger by logger("AgentWebSocketService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true }

    private data class AgentProcessRuntime(
        val runtimeId: String,
        val userId: UUID,
        val workDir: String,
        val configKey: AgentRuntimeConfigKey,
        val bridge: AgentBridgeImpl
    )

    private data class AgentRuntimeConfigKey(
        val workDir: String,
        val isolated: Boolean,
        val configDir: String?,
        val claudeCommand: String?,
        val modelId: String?,
        val baseUrl: String?,
        val provider: String,
        val apiKeyFingerprint: String,
    )

    private data class AgentRuntimeSession(
        val sessionId: String,
        val acpSessionId: String,
        val runtime: AgentProcessRuntime
    )

    private enum class AgentTextDeltaKind {
        OUTPUT,
        THINKING
    }

    private data class AgentStreamFlushPolicy(
        val delayMs: Long,
        val maxChars: Int
    )

    // frontend business sessionId -> ACP session binding in a shared Claude Code process
    private val activeSessions = ConcurrentHashMap<String, AgentRuntimeSession>()
    private val runtimesByUserId = ConcurrentHashMap<UUID, AgentProcessRuntime>()
    private val sessionsByRuntimeId = ConcurrentHashMap<String, MutableSet<String>>()
    private val runtimeMutex = Mutex()

    // sessionId -> cleanup Job
    private val cleanupJobs = ConcurrentHashMap<String, Job>()
    private val sessionStateJobs = ConcurrentHashMap<String, Job>()
    private const val CLEANUP_DELAY_MS = 180_000L // 3 minutes

    // sessionId -> 分析上下文（用户、股票、类型、交易日）
    private data class SessionContext(
        val userId: UUID,
        val tsCode: String? = null,
        val stockName: String? = null,
        val analysisType: String? = null,
        val tradeDate: LocalDate? = null
    )

    private val sessionContexts = ConcurrentHashMap<String, SessionContext>()
    private val lastSessionStatus = ConcurrentHashMap<String, AgentStatus>()
    private val promptContextQueue = ConcurrentHashMap<String, ArrayDeque<SessionContext>>()

    private data class CliToolSpec(
        val toolName: String,
        val installRelativePath: String,
        val gradleTask: String
    )

    fun createSession(
        webSocketSession: DefaultWebSocketServerSession,
        workDir: String?,
        userId: UUID
    ) {
        val defaultWorkDir = "${System.getProperty("user.home")}/.quant_agents/${userId.toString().take(8)}"
        val effectiveWorkDir = workDir ?: defaultWorkDir
        val sessionId = java.util.UUID.randomUUID().toString()
        sessionContexts[sessionId] = SessionContext(userId = userId)

        scope.launch {
            try {
                val agentConfig = ConfigManager.getConfig().agent
                // 查询用户的独占配置
                val dbConfig =
                    try {
                        org.shiroumi
                            .database
                            .user
                            .createUserAgentConfigRepository()
                            .saveOrUpdate(
                                userId = userId,
                                workDir = effectiveWorkDir,
                                isolated = agentConfig.isolated
                            )
                    } catch (e: Exception) {
                        logger.warning(
                            "[AgentWebSocketService] Failed to load/save user agent config: ${e.message}, fallback to default"
                        )
                        null
                    }

                // 路径优先级：数据库覆盖 > 全局配置 > 默认家目录路径
                var resolvedWorkDir = dbConfig?.workDir ?: agentConfig.workDir ?: effectiveWorkDir

                // 确保路径为绝对路径，如果是相对路径则相对于项目根目录 (user.dir)
                val workDirFile = File(resolvedWorkDir).absoluteFile
                resolvedWorkDir = workDirFile.absolutePath

                val isolated = dbConfig?.isolated ?: agentConfig.isolated
                val effectiveModel = AgentModelConfigResolver.resolve(
                    quantConfig = ConfigManager.getConfig(),
                    userConfig = dbConfig
                )
                val runtimeConfigKey = runtimeConfigKey(
                    workDir = resolvedWorkDir,
                    isolated = isolated,
                    configDir = agentConfig.configDir,
                    claudeCommand = agentConfig.claudeCommand,
                    modelId = effectiveModel.modelId,
                    baseUrl = effectiveModel.baseUrl,
                    provider = effectiveModel.provider,
                    apiKey = effectiveModel.apiKey
                )

                // --- 动态技能沙盒 (Skill Sandbox Provisioning) ---
                if (isolated) {
                    try {
                        val projectRoot = File(System.getProperty("quant.project.root") ?: System.getProperty("user.dir"))
                        val baseSkillsDir = listOf(
                            File(projectRoot, "private/agent-analysis-skills"),
                            File(projectRoot, "agent/analysis-skills")
                        ).firstOrNull { it.isDirectory }
                        val userSkillsDir = File(workDirFile, ".claude/skills")
                        userSkillsDir.mkdirs() // 确保目录存在

                        // 清理用户的旧沙盒技能（包括旧的文件夹 and 软链接）
                        userSkillsDir.listFiles()?.forEach {
                            if (java.nio.file.Files.isSymbolicLink(it.toPath())) {
                                it.delete()
                            } else if (it.isDirectory) {
                                it.deleteRecursively()
                            } else {
                                it.delete()
                            }
                        }

                        // 暴露所有技能（通过软链接）
                        if (baseSkillsDir != null) {
                            var linkedCount = 0
                            baseSkillsDir.listFiles()?.forEach { skillDir ->
                                if (skillDir.isDirectory) {
                                    val symlinkPath = File(userSkillsDir, skillDir.name).toPath()
                                    java.nio.file.Files.createSymbolicLink(symlinkPath, skillDir.toPath())
                                    linkedCount++
                                }
                            }
                            logger.info(
                                "[AgentWebSocketService] Provisioned $linkedCount skills for user $userId from ${baseSkillsDir.path} (symlinked)"
                            )
                        } else {
                            logger.warning(
                                "[AgentWebSocketService] No agent analysis skill directory found; " +
                                    "expected private/agent-analysis-skills or agent/analysis-skills"
                            )
                        }

                        // 扫描已挂载的技能并输出诊断日志
                        val skillMgr = org.shiroumi.agent.impl.SkillManager(resolvedWorkDir)
                        val skillDiagnostics = skillMgr.discoverDiagnostics()
                        val skillIndexMarkdown = buildSkillIndexMarkdown(skillDiagnostics)
                        skillDiagnostics.forEach { diagnostic ->
                            if (diagnostic.issues.isEmpty()) {
                                logger.info(
                                    "[AgentWebSocketService] Skill OK name=${diagnostic.name} path=${diagnostic.path} builtin=${diagnostic.builtin} allowedTools=${diagnostic.allowedTools}"
                                )
                            } else {
                                logger.warning(
                                    "[AgentWebSocketService] Skill issues name=${diagnostic.name} path=${diagnostic.path} issues=${diagnostic.issues.joinToString("; ")}"
                                )
                            }
                        }

                        // Write CLAUDE.md to override system prompt and hide native arbitrary tools.
                        val claudeMdFile = File(resolvedWorkDir, "CLAUDE.md")
                        claudeMdFile.writeText(
                            """
                            # 角色设定
                            你是一名专业的量化交易与系统分析 AI Agent。
                            
                            ## Claude Code Skills
                            当前工作目录已挂载 Claude Code 原生 skills。
                            这些 skills 由 Claude Code 原生机制发现和触发；不要自行发明 skill 调用协议、别名、路径别名或手工映射规则。
                            当某个场景需要 skill 时，直接按 Claude Code 原生 skill 心智使用对应 skill，并遵守其 `SKILL.md` 中的约束。
                            如果需要手动读取 Skill 文件，只能读取下面列出的相对路径，不要使用绝对路径或 `../`：

                            $skillIndexMarkdown

                            注意：数学计算不是 Skill。不要查找 `evaluate-math-expressions`，也不要调用 `evaluateMathExpressions`；所有数学计算统一使用 shell 命令 `bc`。
                            
                            ## 可用 CLI 工具
                            以下独立 CLI 工具已作为软链接存在于你的工作目录中，可直接调用：
                            涉及股票代码参数时，统一传带交易所后缀的 ts_code（如 `000001.SZ`、`600519.SH`、`430047.BJ`），不要省略后缀。

                            ### get-candles
                            **用途**：获取指定股票最近N天的日K线数据（前复权）
                            **触发时机**：需要获取特定股票的行情数据用于分析时
                            **参数**：
                            - `--code, -c`：股票代码（统一使用带后缀 ts_code，如 000001.SZ）
                            - `--name, -n`：股票名称（如 平安银行、贵州茅台，精确匹配）
                            - `--limit, -l`：指定获取天数（默认 60）
                            **约束**：--code 和 --name 二选一必填
                            **用法示例**：
                            ```bash
                            ./get-candles --code 000001.SZ
                            ./get-candles --name 平安银行 --limit 30
                            ```

                            ### get-intraday-candles
                            **用途**：获取指定股票的小周期K线数据（60分钟/30分钟/15分钟/5分钟）
                            **触发时机**：需要获取特定股票的盘中K线数据用于短期技术分析时
                            **参数**：
                            - `--code, -c`：股票代码（统一使用带后缀 ts_code，如 000001.SZ）
                            - `--name, -n`：股票名称（如 平安银行、贵州茅台，精确匹配）
                            - `--period, -p`：K线周期，支持 60min/30min/15min/5min（默认 60min）
                            - `--limit, -l`：获取数据条数（默认 100）
                            **约束**：--code 和 --name 二选一必填
                            **用法示例**：
                            ```bash
                            ./get-intraday-candles --code 000001.SZ --period 30min
                            ./get-intraday-candles --name 贵州茅台 --period 5min --limit 50
                            ```

                            ### get-research-reports
                            **用途**：获取指定股票对应的券商研究报告
                            **触发时机**：需要查看券商观点、研报摘要、机构覆盖情况时
                            **参数**：
                            - `--code, -c`：股票代码（统一使用带后缀 ts_code，如 000001.SZ）
                            - `--name, -n`：股票名称（如 平安银行、贵州茅台，精确匹配）
                            - `--start-date`：开始日期（YYYYMMDD）
                            - `--end-date`：结束日期（YYYYMMDD）
                            - `--trade-date`：单日查询日期（YYYYMMDD）
                            - `--report-type`：研报类型（如 个股研报）
                            - `--inst`：券商机构简称
                            - `--limit, -l`：返回条数（默认 20）
                            - `--format`：输出格式（默认 json）
                            **约束**：
                            - `--code` 和 `--name` 二选一必填
                            - 使用 `--code` 时统一传带交易所后缀的 ts_code
                            - 默认查询最近 90 天
                            - 当前仅支持 JSON 输出
                            **用法示例**：
                            ```bash
                            ./get-research-reports --code 000001.SZ
                            ./get-research-reports --code 000001.SZ --limit 5
                            ./get-research-reports --name 平安银行 --report-type 个股研报
                            ```

                            ### get-industry-research-reports
                            **用途**：获取指定行业对应的券商行业研报
                            **触发时机**：需要查看某个行业的研究观点、摘要和机构覆盖情况时
                            **参数**：
                            - `--ind-name`：行业名称（如 半导体、银行、人工智能）
                            - `--start-date`：开始日期（YYYYMMDD）
                            - `--end-date`：结束日期（YYYYMMDD）
                            - `--trade-date`：单日查询日期（YYYYMMDD）
                            - `--inst`：券商机构简称
                            - `--limit, -l`：返回条数（默认 20）
                            - `--format`：输出格式（默认 json）
                            **约束**：
                            - `report_type` 固定为行业研报
                            - 默认查询最近 90 天
                            - 当前仅支持 JSON 输出
                            **用法示例**：
                            ```bash
                            ./get-industry-research-reports --ind-name 半导体
                            ./get-industry-research-reports --ind-name 银行 --limit 5
                            ./get-industry-research-reports --ind-name 人工智能 --inst 中信证券
                            ```

                            ### get-limit-list
                            **用途**：查询指定股票涨跌停、炸板和封板强度数据（Tushare limit_list_d）
                            **触发时机**：买卖点分析、涨停强度判断、连板/炸板/封单强弱判断时必须调用
                            **参数**：
                            - `--code, -c`：股票代码（统一使用带后缀 ts_code，如 000001.SZ）
                            - `--name, -n`：股票名称（如 平安银行、贵州茅台，精确匹配）
                            - `--start-date`：开始日期（YYYYMMDD）
                            - `--end-date`：结束日期（YYYYMMDD）
                            - `--trade-date`：单日查询日期（YYYYMMDD）
                            - `--limit-type`：涨跌停类型，U=涨停，D=跌停，Z=炸板
                            - `--limit, -l`：返回条数（默认 20）
                            - `--format`：输出格式（默认 json）
                            **约束**：
                            - `--code` 和 `--name` 二选一必填
                            - 使用 `--code` 时统一传带交易所后缀的 ts_code
                            - 默认查询最近 60 天
                            - 当前仅支持 JSON 输出
                            **重点字段**：
                            - `openTimes`：打开次数，>0 表示涨停过程中曾开板
                            - `limitType`/`limit`：U涨停、D跌停、Z炸板
                            - `firstTime`/`lastTime`：首次/最后封板时间
                            - `fdAmount`：封单金额
                            - `limitAmount`：板上成交金额
                            - `upStat`/`limitTimes`：连板统计
                            **用法示例**：
                            ```bash
                            ./get-limit-list --code 002975.SZ
                            ./get-limit-list --code 002975.SZ --limit-type U --limit 10
                            ./get-limit-list --code 002975.SZ --start-date 20260401 --end-date 20260425
                            ```

                            ### market-emotion
                            **用途**：获取市场情绪参数
                            **触发时机**：需要获取市场情绪上下文时
                            **参数**：无
                            **输出**：
                            - sentimentExposure: 最终仓位系数 0.0-1.0
                            - bullRatio: 全市场看涨比例 (>0.4为良性多头市场)
                            - marketVol: 全市场波动率 (>0.05为高波脆弱市场)
                            - fftScore: FFT相位得分
                            - emptyReason: 空仓原因
                            **最终报告表达约束**：
                            - 这些输出字段名只允许作为内部读数使用，最终 Markdown 正文和自定义块展示文本中禁止出现字段名本身。
                            - 必须把字段翻译成人能理解的业务语言：sentimentExposure → 市场参与意愿/仓位水位；bullRatio → 看涨扩散度/多头覆盖面；marketVol → 盘面波动水平；fftScore → 周期共振强弱；emptyReason → 防守或空仓原因。
                            - 可以保留关键数值，但必须用自然语言解释，不要写成“bullRatio 0.396”“marketVol 0.025”“fftScore 0.051”这种参数清单。
                            **用法示例**：
                            ```bash
                            ./market-emotion
                            ```

                            ## 报告输出格式规范

                            ### 最终报告语言规则（绝对不可违反）

                            1. 工具返回的字段名、JSON 字段名、程序员式参数名只允许用于内部推理或 fenced code block 的机器字段，不得出现在最终展示给用户阅读的自然语言正文中。
                            2. 最终 Markdown 正文，以及 `quant-limit-up` / `quant-volume-price` / `quant-market-sentiment` 中所有会展示给用户看的字符串字段，都必须是自然语言。
                            3. 禁止在最终报告展示文本中出现以下词：sentimentExposure、bullRatio、marketVol、fftScore、accelZ、volZ、emptyReason、residualScore、limitType、openTimes、fdAmount、limitAmount、upStat。
                            4. 应改写为：市场参与意愿、建议仓位水位、看涨扩散度、上涨家数覆盖面、盘面波动水平、趋势节奏共振、情绪变化速度、封板打开次数、封单规模、板上成交额、连板状态。
                            5. 反例：不要写“sentimentExposure 0.488 中性偏低，bullRatio 0.396 不足 40%，marketVol 0.025 正常”。正确写法：“今天市场参与意愿偏谨慎，看涨扩散度不足四成；虽然盘面波动没有失控，但上涨缺少足够多股票的共同支持。”
                            6. 反例：不要写“在 bullRatio < 0.4 的环境中”。正确写法：“在看涨扩散度不足四成的偏弱环境中”。

                            ### 报告头部（quant-header）— 必须

                            **所有分析报告必须以 `quant-header` 代码块开头**，采用杂志封面式设计。

                            #### 格式

                            ````markdown
                            ```quant-header
                            {
                              "title": "出入点分析",
                              "stockCode": "002975.SZ",
                              "stockName": "博杰股份",
                              "analysisType": "买卖点分析",
                              "tradeDate": "2026-04-24"
                            }
                            ```
                            ````

                            #### 字段说明

                            - `title`（必填）：报告标题，**一句话总结，不超过16个字**，**不包含股票代码**
                              - ✅ 正确：`回踩买点机会`、`突破后回调风险`、`震荡整理待突破`
                              - ❌ 错误：`平安银行（000001.SZ）买卖点分析`、`详细的技术分析报告`
                            - `stockCode`（必填）：股票代码（如 `002975.SZ`）
                            - `stockName`（推荐）：股票名称（如 `博杰股份`）
                            - `analysisType`（必填）：分析类型（如 `买卖点分析`、`趋势分析`、`研报分析`）
                            - `tradeDate`（推荐）：交易日期（如 `2026-04-24`）

                            #### 核心约束

                            1. **必须放在报告最开头**，在任何 Markdown 内容之前
                            2. **标题不能包含股票代码**，股票代码通过 `stockCode` 字段提供
                            3. **JSON 必须是标准 JSON**，不能写注释
                            4. **所有报告都必须有 `quant-header`**，无论是买卖点分析、趋势分析还是研报分析
                            5. **不要在 `quant-header` 之后再输出重复的 Markdown 标题**（如 `# 平安银行出入点分析`），因为 `quant-header` 已经包含了标题信息

                            #### 完整示例

                            ````markdown
                            ```quant-header
                            {
                              "title": "回踩买点机会",
                              "stockCode": "000001.SZ",
                              "stockName": "平安银行",
                              "analysisType": "买卖点分析",
                              "tradeDate": "2026-04-24"
                            }
                            ```

                            ## 大周期环境

                            日线级别，平安银行当前处于上升趋势中...
                            ````

                            ### K 线图嵌入（quant-kline）

                            在分析报告中，你可以通过 fenced code block 声明 K 线图，前端会渲染为图表。
                            这是让报告更直观的重要手段，**请在所有分析报告中都包含至少一个 K 线图**。

                            #### 规则

                            1. 报告头部 `quant-header` 之后，通常紧跟一个日K线图用于展示大周期背景。
                            2. 小周期买卖点分析必须按周期分别做条件触发扫描：60分钟、30分钟、15分钟，必要时补充5分钟。扫描目标不是判断当前已经有没有信号，而是以当前价格为起点，推演价格如果突破、回踩、下探到支撑或跌破关键位，会触发什么买点/卖点。
                            3. 每个小周期可以形成一个或多个候选交易模式；每组候选模式都要给出触发条件、买点、卖点、止损、目标位、盈亏比和对应周期自己的 `quant-kline`。
                            4. 最终要综合比较所有小周期候选模式，选择盈亏比最高且结构质量可靠的主交易模式；如果盈亏比最高但结构弱，要用自然语言说明取舍。
                            5. 涉及买卖点的小周期图只能展示局部交易窗口：根据该周期候选模式选择最能说明问题的一段，`maxCandles` 必须 <= 60，优先用 `focusDate` 对准预计触发买卖点；如果触发点尚未发生，使用最接近触发区间的当前/最近 K 线。
                            6. `tsCode` 必须使用带交易所后缀的完整代码。
                            7. `endDate` 必须写入本次分析对应的交易日，**绝对不能**省略。
                            8. 同一篇报告最多 3 个 K 线图。
                            9. JSON 必须是标准 JSON，不能写注释。
                            10. 每一组条件触发买点/卖点、止损位、目标位或盈亏比，必须在对应小周期 `quant-kline` 中写入 `markers` 和 `tradePlan`，让前端绘制标记与盈亏比区间。
                            11. 不要使用机械状态标签。用自然语言说明当前价格位置、各周期价格怎么变化才触发交易、哪组模式最优。

                            ### 格式

                            ````markdown
                            ```quant-kline
                            {
                              "tsCode": "600519.SH",
                              "period": "DAY",
                              "startDate": "2025-10-01",
                              "endDate": "2026-04-24",
                              "height": 360,
                              "indicators": ["MA20", "VOLUME"]
                            }
                            ```
                            ````

                            **字段说明**：
                            - `period`: DAY / WEEK / MONTH / MIN_60 / MIN_30 / MIN_15 / MIN_5
                            - `indicators`: 可选 ["MA20", "EMA20", "VOLUME", "RSI", "MACD", "BOLL"]
                            - `maxCandles`: 可选，小周期买卖点图必须设置，最大 60
                            - `focusDate`: 可选，局部窗口聚焦的 K 线时间；分钟线可写完整时间，如 "2026-04-24 10:30:00"
                            - `markers`: 可选，买卖点标记（date + type=BUY/SELL + label + 可选 price）
                            - `tradePlan`: 可选，交易计划标记（side + entryPrice + stopLossPrice + targetPrice + 可选 riskRewardRatio）

                            ### 日K线图示例（放报告开头）

                            ````markdown
                            # 分析报告标题

                            ```quant-kline
                            {
                              "tsCode": "000001.SZ",
                              "period": "DAY",
                              "startDate": "2025-10-01",
                              "endDate": "2026-04-24",
                              "indicators": ["MA20", "VOLUME"]
                            }
                            ```
                            ````

                            ### 小周期图示例（放买卖点分析处）

                            ````markdown
                            ```quant-kline
                            {
                              "tsCode": "000001.SZ",
                              "period": "MIN_60",
                              "startDate": "2026-03-20",
                              "endDate": "2026-04-24",
                              "maxCandles": 60,
                              "focusDate": "2026-04-24 10:30:00",
                              "height": 360,
                              "indicators": ["MA20", "VOLUME"],
                              "markers": [
                                {"date": "2026-04-24 10:30:00", "type": "BUY", "label": "突破入场", "price": 12.35},
                                {"date": "2026-04-24 14:30:00", "type": "SELL", "label": "目标止盈", "price": 13.10}
                              ],
                              "tradePlan": {
                                "side": "BUY",
                                "entryPrice": 12.35,
                                "stopLossPrice": 12.05,
                                "targetPrice": 13.10,
                                "riskRewardRatio": 2.5,
                                "entryLabel": "买点",
                                "stopLabel": "止损",
                                "targetLabel": "目标"
                              }
                            }
                            ```
                            ````

                            ## 核心约束 (HARD CONSTRAINTS — 绝对不可违反)

                            ### 1. 文件系统访问限制
                            你只能读取和操作**当前工作目录**（本目录）内的文件。
                            严禁访问任何当前目录以外的路径（包括 `/Users/...`、`~/...` 绝对路径或 `../` 上级目录路径）。

                            ### 2. 严禁执行 Python 脚本
                            **绝对禁止**通过终端运行任何 Python 脚本或命令，包括但不限于：
                            - `python ...` / `python3 ...`
                            - `.venv/bin/python ...` / `/path/to/python ...`
                            - `uv run python ...`

                            在任何情况下，你都不得直接在终端中调用 Python 解释器。

                            ### 3. Skill 是行为准则，必须严格遵守
                            `.claude/skills/` 目录下的每一个 Skill 不仅是工具说明，更是**行为准则和强制执行规范**。
                            当某个场景触发了对应 Skill 时，你必须：
                            - 先读取该 Skill 的完整 `SKILL.md` 文件
                            - 严格按照其中规定的步骤和约束执行，不得跳过任何步骤
                            - Skill 中明确禁止的行为，即使你认为有更简便的方式，也**绝对不能**绕过

                            ### 4. 所有数学计算必须使用 bc
                            涉及任何数值计算（包括但不限于：百分比、价格区间、风险收益比、止损位、仓位大小等）时，
                            应通过 shell 命令 `bc` 得到准确结果，并显式控制小数精度。
                            例如：`echo "scale=4; (10.5 - 9.8) / 9.8 * 100" | bc`
                            严禁在回复中直接输出未经工具验证的"心算"结果。

                            ## 回复风格 (COMMUNICATION STYLE)
                            1. 极度精简重点：绝对不要说"好的"、"收到"、"我这就去"、"请稍等"等凑字数的废话。
                            2. 结果导向：在被要求查询数据或执行计算动作时，你**必须**第一步直接且静默地调用相关的能力工具。只有在拿到底层工具的返回结果后，再组织并输出唯一一次完整专业的结论。
                        """.trimIndent()
                        )

                        // Provision CLI tools with correct server port
                        val projectDir = System.getProperty("quant.project.root")
                            ?: System.getProperty("user.dir")
                        val serverPort = ConfigManager.load().let { cfg ->
                            cfg.server.port
                        }
                        val tools = listOf(
                            CliToolSpec(
                                toolName = "get-candles",
                                installRelativePath = "tools/get-candles/build/install/get-candles/bin/get-candles",
                                gradleTask = ":tools:get-candles:installDist"
                            ),
                            CliToolSpec(
                                toolName = "get-intraday-candles",
                                installRelativePath = "tools/get-intraday-candles/build/install/get-intraday-candles/bin/get-intraday-candles",
                                gradleTask = ":tools:get-intraday-candles:installDist"
                            ),
                            CliToolSpec(
                                toolName = "get-research-reports",
                                installRelativePath = "tools/get-research-reports/build/install/get-research-reports/bin/get-research-reports",
                                gradleTask = ":tools:get-research-reports:installDist"
                            ),
                            CliToolSpec(
                                toolName = "get-industry-research-reports",
                                installRelativePath = "tools/get-industry-research-reports/build/install/get-industry-research-reports/bin/get-industry-research-reports",
                                gradleTask = ":tools:get-industry-research-reports:installDist"
                            ),
                            CliToolSpec(
                                toolName = "get-limit-list",
                                installRelativePath = "tools/get-limit-list/build/install/get-limit-list/bin/get-limit-list",
                                gradleTask = ":tools:get-limit-list:installDist"
                            ),
                            CliToolSpec(
                                toolName = "market-emotion",
                                installRelativePath = "tools/market-emotion/build/install/market-emotion/bin/market-emotion",
                                gradleTask = ":tools:market-emotion:installDist"
                            )
                        )

                        tools.forEach { spec ->
                            val toolSource = File(projectDir, spec.installRelativePath)
                            val toolLauncher = File(resolvedWorkDir, spec.toolName)

                            if (java.nio.file.Files.isSymbolicLink(toolLauncher.toPath()) || toolLauncher.exists()) {
                                toolLauncher.delete()
                            }

                            if (toolSource.exists()) {
                                writeToolWrapperScript(toolLauncher, toolSource.absolutePath, serverPort)
                                logger.info("[AgentWebSocketService] Created wrapper for tool: ${spec.toolName}")
                            } else {
                                writeToolBootstrapScript(toolLauncher, projectDir, spec, serverPort)
                                logger.warning(
                                    "[AgentWebSocketService] Tool source not found, wrote bootstrap launcher for ${spec.toolName}: ${toolSource.absolutePath}"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "[AgentWebSocketService] Failed to provision skill sandbox: ${e.message}"
                        )
                    }
                }

                logger.info(
                    "[AgentWebSocketService] ▶ createSession " +
                            "sessionId=$sessionId userId=$userId workDir=$resolvedWorkDir " +
                            "claudeCommand=${agentConfig.claudeCommand ?: "auto"} " +
                            "isolated=$isolated " +
                            "model=${effectiveModel.modelId ?: "default"} " +
                            "apiKey=${if (effectiveModel.apiKey.isBlank()) "ENV" else "configured(masked)"}"
                )

                val runtime = getOrCreateRuntime(
                    userId = userId,
                    workDir = resolvedWorkDir,
                    configKey = runtimeConfigKey,
                    config = org.shiroumi.agent.api.AgentBridge.Config(
                        workDir = resolvedWorkDir,
                        claudeCommand = agentConfig.claudeCommand,
                        isolated = isolated,
                        apiKey = effectiveModel.apiKey,
                        configDir = agentConfig.configDir,
                        modelId = effectiveModel.modelId,
                        baseUrl = effectiveModel.baseUrl,
                        provider = effectiveModel.provider,
                        onProcessStarted = { pid ->
                            org.shiroumi.server.agent.AgentProcessRegistry.register(
                                sessionId = runtimeRegistryId(userId),
                                pid = pid,
                                workDir = resolvedWorkDir
                            )
                        }
                    )
                )
                val acpSessionId = try {
                    withTimeout(10_000L) { runtime.bridge.createSession(resolvedWorkDir) }
                } catch (e: TimeoutCancellationException) {
                    logger.error(
                        "[AgentWebSocketService] ✘ createSession timed out after 30s sessionId=$sessionId — invalidating broken runtime"
                    )
                    runtimeMutex.withLock { invalidateRuntime(runtime) }
                    throw RuntimeException("ACP session creation timed out after 30s")
                }
                if (!AppWebSocketConnectionManager.isSessionActive(webSocketSession)) {
                    logger.info(
                        "[AgentWebSocketService] Origin websocket closed before ACP session bind; " +
                            "closing orphan sessionId=$sessionId acpSessionId=$acpSessionId"
                    )
                    closeUnboundRuntimeSession(runtime, acpSessionId)
                    cleanupSessionBookkeeping(sessionId)
                    return@launch
                }
                activeSessions[sessionId] = AgentRuntimeSession(
                    sessionId = sessionId,
                    acpSessionId = acpSessionId,
                    runtime = runtime
                )
                sessionsByRuntimeId.computeIfAbsent(runtime.runtimeId) { ConcurrentHashMap.newKeySet() }
                    .add(sessionId)
                logger.info(
                    "[AgentWebSocketService] ✔ ACP session bound sessionId=$sessionId acpSessionId=$acpSessionId runtimeId=${runtime.runtimeId} activeSessions=${activeSessions.size}"
                )

                sessionStateJobs.remove(sessionId)?.cancel()
                sessionStateJobs[sessionId] = scope.launch {
                    // 订阅细粒度增量流：每个 ACP chunk / 工具事件都被翻译成一条 wire Delta，
                    // 不再用 StateFlow 的「下游处理上一帧时新值覆盖」模式（那会在公网慢链路下
                    // 让 ktor 一侧就把中间帧丢光，前端只能在终态拿到完整 output）。
                    //
                    // observeState 仍然保留：终态帧、订阅瞬间快照、持久化等场景需要累积视图。
                    val aggregator = AgentTextDeltaAggregator(sessionId, scope) { delta ->
                        sendAgentStreamDelta(sessionId, WsAction.UPDATE, delta)
                    }
                    try {
                        runtime.bridge.observeUpdates(acpSessionId).collect { update ->
                            pushUpdate(sessionId, acpSessionId, runtime, update, aggregator)
                        }
                    } finally {
                        aggregator.close()
                    }
                }

                logger.info("[AgentWebSocketService] ✔ Agent session ready: $sessionId")

                AppWebSocketConnectionManager.sendToSession(
                    session = webSocketSession,
                    event = WsEvent(
                        topic = WsTopic.AGENT_SESSION,
                        action = WsAction.SYNC,
                        targetId = sessionId,
                        payload = sessionId
                    )
                )
                if (!AppWebSocketConnectionManager.isSessionActive(webSocketSession)) {
                    logger.info(
                        "[AgentWebSocketService] Origin websocket closed after ACP session bind; " +
                            "closing orphan sessionId=$sessionId acpSessionId=$acpSessionId"
                    )
                    closeSession(sessionId)
                } else if (AppWebSocketConnectionManager.getSubscriberCount(sessionId) == 0) {
                    scheduleIdleCleanup(sessionId, "session created without subscriber")
                }
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ createSession failed sessionId=$sessionId: ${e::class.simpleName}: ${e.message}"
                )
                pushSessionError(webSocketSession, sessionId, "创建 Agent 会话失败: ${e.message}")
                cleanupSessionBookkeeping(sessionId)
            }
        }
    }

    private fun writeToolWrapperScript(
        launcherFile: File,
        targetBinPath: String,
        serverPort: Int
    ) {
        launcherFile.writeText(
            """
            |#!/usr/bin/env bash
            |export QUANT_SERVER_PORT=$serverPort
            |exec ${shellQuote(targetBinPath)} "${'$'}@"
            """.trimMargin()
        )
        launcherFile.setExecutable(true)
    }

    private fun writeToolBootstrapScript(
        launcherFile: File,
        projectDir: String,
        spec: CliToolSpec,
        serverPort: Int
    ) {
        launcherFile.writeText(
            """
            |#!/usr/bin/env bash
            |set -euo pipefail
            |
            |export QUANT_SERVER_PORT=$serverPort
            |PROJECT_DIR=${shellQuote(projectDir)}
            |TARGET_BIN=${shellQuote("${projectDir}/${spec.installRelativePath}")}
            |
            |cd "${'$'}PROJECT_DIR"
            |
            |if [ ! -x "${'$'}TARGET_BIN" ]; then
            |  ./gradlew --quiet ${spec.gradleTask}
            |fi
            |
            |exec "${'$'}TARGET_BIN" "${'$'}@"
            """.trimMargin()
        )
        launcherFile.setExecutable(true)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun buildSkillIndexMarkdown(
        diagnostics: List<org.shiroumi.agent.impl.SkillDiagnostic>
    ): String {
        if (diagnostics.isEmpty()) {
            return "- 当前未发现可用 Skill。"
        }
        return diagnostics
            .sortedBy { it.name }
            .joinToString("\n") { diagnostic ->
                "- `${diagnostic.name}`: `${diagnostic.path}`"
            }
    }

    private fun runtimeRegistryId(userId: UUID): String = "agent-runtime-${userId}"

    private suspend fun getOrCreateRuntime(
        userId: UUID,
        workDir: String,
        configKey: AgentRuntimeConfigKey,
        config: AgentBridge.Config
    ): AgentProcessRuntime = runtimeMutex.withLock {
        runtimesByUserId[userId]?.let { runtime ->
            if (runtime.bridge.isHealthy() && runtime.configKey == configKey) {
                logger.info(
                    "[AgentWebSocketService] Reusing Claude Code ACP runtime runtimeId=${runtime.runtimeId} userId=$userId workDir=${runtime.workDir}"
                )
                return@withLock runtime
            }
            if (runtime.bridge.isHealthy() && runtime.configKey != configKey) {
                val runtimeSessions = sessionsByRuntimeId[runtime.runtimeId].orEmpty()
                if (runtimeSessions.isNotEmpty()) {
                    logger.info(
                        "[AgentWebSocketService] Runtime config changed but existing sessions remain; " +
                            "reusing old runtime until sessions close runtimeId=${runtime.runtimeId} userId=$userId sessions=${runtimeSessions.size}"
                    )
                    return@withLock runtime
                }
                logger.info(
                    "[AgentWebSocketService] Runtime config changed and runtime is idle; recreating runtimeId=${runtime.runtimeId} userId=$userId"
                )
                invalidateRuntime(runtime)
            } else {
                logger.warning(
                    "[AgentWebSocketService] ACP runtime is unhealthy runtimeId=${runtime.runtimeId} userId=$userId — shutting down and recreating"
                )
                invalidateRuntime(runtime)
            }
        }

        val bridge = AgentBridgeImpl()
        bridge.launch(config)
        val runtime = AgentProcessRuntime(
            runtimeId = runtimeRegistryId(userId),
            userId = userId,
            workDir = workDir,
            configKey = configKey,
            bridge = bridge
        )
        runtimesByUserId[userId] = runtime
        logger.info(
            "[AgentWebSocketService] ✔ Claude Code ACP runtime launched runtimeId=${runtime.runtimeId} userId=$userId workDir=$workDir"
        )
        runtime
    }

    fun hasRuntimeConfigDrift(
        userId: UUID,
        modelId: String?,
        baseUrl: String?,
        apiKey: String,
        provider: String
    ): Boolean {
        val agentConfig = ConfigManager.getConfig().agent
        val runtime = runtimesByUserId[userId] ?: return false
        val desired = runtimeConfigKey(
            workDir = runtime.workDir,
            isolated = runtime.configKey.isolated,
            configDir = agentConfig.configDir,
            claudeCommand = agentConfig.claudeCommand,
            modelId = modelId,
            baseUrl = baseUrl,
            provider = provider,
            apiKey = apiKey
        )
        return runtime.configKey != desired
    }

    private fun runtimeConfigKey(
        workDir: String,
        isolated: Boolean,
        configDir: String?,
        claudeCommand: String?,
        modelId: String?,
        baseUrl: String?,
        provider: String,
        apiKey: String,
    ): AgentRuntimeConfigKey = AgentRuntimeConfigKey(
        workDir = workDir,
        isolated = isolated,
        configDir = configDir,
        claudeCommand = claudeCommand,
        modelId = modelId,
        baseUrl = baseUrl,
        provider = provider,
        apiKeyFingerprint = apiKeyFingerprint(apiKey)
    )

    private fun apiKeyFingerprint(apiKey: String): String {
        if (apiKey.isBlank()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes)
    }

    private suspend fun closeUnboundRuntimeSession(
        runtime: AgentProcessRuntime,
        acpSessionId: String
    ) {
        runCatching {
            runtime.bridge.closeSession(acpSessionId)
        }.onFailure { error ->
            logger.warning(
                "[AgentWebSocketService] Failed to close unbound ACP session acpSessionId=$acpSessionId: ${error.message}"
            )
        }
        runtimeMutex.withLock {
            if (sessionsByRuntimeId[runtime.runtimeId].isNullOrEmpty()) {
                runtimesByUserId.remove(runtime.userId, runtime)
                runtime.bridge.shutdown()
                org.shiroumi.server.agent.AgentProcessRegistry.unregister(runtime.runtimeId)
                logger.info(
                    "[AgentWebSocketService] Shut down idle runtime after orphan create runtimeId=${runtime.runtimeId}"
                )
            }
        }
    }

    /**
     * 清理不健康的 Runtime：从注册表中移除、关闭 bridge、注销进程。
     * 调用方必须持有 runtimeMutex。
     */
    private fun invalidateRuntime(runtime: AgentProcessRuntime) {
        val runtimeSessionIds = sessionsByRuntimeId.remove(runtime.runtimeId).orEmpty().toList()
        runtimeSessionIds.forEach { sessionId ->
            activeSessions.remove(sessionId)
            cleanupSessionBookkeeping(sessionId)
        }
        runtimesByUserId.remove(runtime.userId, runtime)
        runtime.bridge.shutdown()
        org.shiroumi.server.agent.AgentProcessRegistry.unregister(runtime.runtimeId)
        logger.info(
            "[AgentWebSocketService] ✔ Invalidated and shut down unhealthy runtime runtimeId=${runtime.runtimeId}"
        )
    }

    fun closeSession(sessionId: String) {
        cleanupSessionBookkeeping(sessionId)
        activeSessions.remove(sessionId)?.let { runtimeSession ->
            scope.launch {
                try {
                    logger.info(
                        "[AgentWebSocketService] ▶ closeSession sessionId=$sessionId activeSessions=${activeSessions.size}"
                    )
                    runtimeSession.runtime.bridge.closeSession(runtimeSession.acpSessionId)
                    val runtimeSessions = sessionsByRuntimeId[runtimeSession.runtime.runtimeId]
                    runtimeSessions?.remove(sessionId)
                    if (runtimeSessions == null || runtimeSessions.isEmpty()) {
                        sessionsByRuntimeId.remove(runtimeSession.runtime.runtimeId)
                        runtimesByUserId.remove(runtimeSession.runtime.userId, runtimeSession.runtime)
                        runtimeSession.runtime.bridge.shutdown()
                        org.shiroumi.server.agent.AgentProcessRegistry.unregister(runtimeSession.runtime.runtimeId)
                    }

                    logger.info(
                        "[AgentWebSocketService] ✔ Session closed: $sessionId activeSessions=${activeSessions.size}"
                    )
                } catch (e: Exception) {
                    logger.warning("Error closing Agent session $sessionId: ${e.message}")
                }
            }
        }
    }

    suspend fun resetUserRuntime(userId: UUID) {
        runtimeMutex.withLock {
            val runtime = runtimesByUserId[userId] ?: return
            invalidateRuntime(runtime)
            logger.info(
                "[AgentWebSocketService] ✔ Reset runtime after Agent model switch userId=$userId runtimeId=${runtime.runtimeId}"
            )
        }
    }

    fun shutdown() {
        cleanupJobs.values.forEach { it.cancel() }
        cleanupJobs.clear()
        sessionStateJobs.values.forEach { it.cancel() }
        sessionStateJobs.clear()
        sessionContexts.clear()
        lastSessionStatus.clear()
        promptContextQueue.clear()
        activeSessions.clear()
        sessionsByRuntimeId.clear()
        runtimesByUserId.values.forEach { runtime ->
            runtime.bridge.shutdown()
            org.shiroumi.server.agent.AgentProcessRegistry.unregister(runtime.runtimeId)
        }
        runtimesByUserId.clear()
    }

    private fun cleanupSessionBookkeeping(sessionId: String) {
        cleanupJobs.remove(sessionId)?.cancel()
        sessionStateJobs.remove(sessionId)?.cancel()
        sessionContexts.remove(sessionId)
        lastSessionStatus.remove(sessionId)
        promptContextQueue.remove(sessionId)
    }

    /**
     * 当有新的 WebSocket 订阅者加入时调用
     * 如果该会话正处于清理计时中，则取消计时
     */
    fun onSubscriberJoined(sessionId: String) {
        cleanupJobs.remove(sessionId)?.cancel()
        if (activeSessions.containsKey(sessionId)) {
            logger.info("[AgentWebSocketService] Subscriber joined for session $sessionId, cleanup cancelled.")
        }
    }

    /**
     * 当 WebSocket 订阅者离开（取消订阅或断开连接）时调用
     * 如果订阅人数归零，则启动 3 分钟清理计时
     */
    fun onSubscriberLeft(sessionId: String) {
        if (!activeSessions.containsKey(sessionId)) return

        val count = AppWebSocketConnectionManager.getSubscriberCount(sessionId)
        if (count == 0) {
            scheduleIdleCleanup(sessionId, "last subscriber left")
        } else {
            logger.info("[AgentWebSocketService] Subscriber left for session $sessionId, but $count subscribers remain.")
        }
    }

    private fun scheduleIdleCleanup(sessionId: String, reason: String) {
        if (!activeSessions.containsKey(sessionId)) return
        logger.info("[AgentWebSocketService] $reason for session $sessionId, scheduling cleanup in 3 mins.")
        cleanupJobs.remove(sessionId)?.cancel()
        val job = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CLEANUP_DELAY_MS)
                val runtimeSession = activeSessions[sessionId] ?: return@launch
                val subscriberCount = AppWebSocketConnectionManager.getSubscriberCount(sessionId)
                if (subscriberCount > 0) {
                    logger.info(
                        "[AgentWebSocketService] Cleanup timer ignored for session $sessionId, subscribers=$subscriberCount"
                    )
                    return@launch
                }
                val status = runtimeSession.runtime.bridge.observeState(runtimeSession.acpSessionId).value.status
                if (status == AgentStatus.THINKING || status == AgentStatus.EXECUTING) {
                    logger.info(
                        "[AgentWebSocketService] Cleanup timer deferred for active session $sessionId status=$status"
                    )
                    continue
                }
                break
            }
            logger.info("[AgentWebSocketService] Cleanup timer expired for idle session $sessionId, closing...")
            closeSession(sessionId)
        }
        cleanupJobs[sessionId] = job
    }

    fun sendPrompt(sessionId: String, promptPayload: String) {
        logger.info("[AgentWebSocketService] ▶ sendPrompt CMD received sessionId=$sessionId")
        val runtimeSession =
            activeSessions[sessionId]
                ?: run {
                    logger.warning(
                        "[AgentWebSocketService] ✘ sendPrompt: session not found sessionId=$sessionId"
                    )
                    return
                }

        val promptRequest = try {
            json.decodeFromString<AgentPromptRequest>(promptPayload)
        } catch (e: Exception) {
            logger.warning(
                "[AgentWebSocketService] ✘ sendPrompt: invalid AgentPromptRequest sessionId=$sessionId: ${e.message}"
            )
            scope.launch { pushError(sessionId, "Prompt 请求格式错误") }
            return
        }

        // 更新会话上下文：context=null 表示本轮显式无上下文。
        val updatedCtx = sessionContexts.computeIfPresent(sessionId) { _, ctx ->
            val c = promptRequest.context
            when {
                c == null -> ctx.copy(
                    tsCode = null,
                    stockName = null,
                    analysisType = null,
                    tradeDate = null
                )
                else -> ctx.copy(
                    tsCode = c.tsCode,
                    stockName = c.stockName,
                    analysisType = c.analysisType,
                    tradeDate = c.tradeDate?.let { dateStr -> runCatching { LocalDate.parse(dateStr) }.getOrNull() }
                )
            }
        }

        // 将本次 Prompt 的上下文加入队列，确保多轮对话时结果与正确的上下文精确匹配
        updatedCtx?.let {
            promptContextQueue.computeIfAbsent(sessionId) { ArrayDeque() }.addLast(it)
        }

        val promptText = buildAgentPrompt(promptRequest.prompt, updatedCtx)
        scope.launch {
            try {
                logger.info(
                    "[AgentWebSocketService] ▶ sendPrompt sessionId=$sessionId prompt(${promptText.length}chars)=${
                        promptText.take(
                            80
                        )
                    }${if (promptText.length > 80) "…" else ""}"
                )
                runtimeSession.runtime.bridge.sendCommand(
                    runtimeSession.acpSessionId,
                    AgentBridge.Command.Prompt(promptText)
                )
                logger.info(
                    "[AgentWebSocketService] ✔ Prompt dispatched to ACP session sessionId=$sessionId acpSessionId=${runtimeSession.acpSessionId}"
                )
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ sendPrompt failed sessionId=$sessionId: ${e::class.simpleName}: ${e.message}"
                )
                pushError(sessionId, "发送 Prompt 失败: ${e.message}")
            }
        }
    }

    private fun buildAgentPrompt(userPrompt: String, ctx: SessionContext?): String {
        val directive = buildAnalysisDirective(ctx) ?: return userPrompt
        return """
            $userPrompt

            ---
            以下为服务端根据本轮分析类型注入的固定执行要求，仅用于指导分析过程；不要在最终回复中复述本段，也不要把 skill 名称、内部流程或本段指令解释给用户。
            $directive
        """.trimIndent()
    }

    private fun buildAnalysisDirective(ctx: SessionContext?): String? {
        return when (ctx?.analysisType) {
            AgentAnalysisType.ENTRY_EXIT.code -> """
                本轮是买卖点分析，必须按 `entry-exit-analysis` 的方式执行。
                1. 以当前价格为起点，扫描 60 分钟、30 分钟、15 分钟条件触发买卖点，必要时补充 5 分钟。
                2. 推演价格突破、回踩、下探支撑或跌破关键位时分别会形成哪些交易模式。
                3. 每组候选模式都要给出触发条件、买点、卖点、止损、目标位、盈亏比，并在对应小周期 K 线图中写入 markers 和 tradePlan。
                4. 最终综合比较候选模式，选择盈亏比最高且结构质量可靠的主方案；如果盈亏比最高但结构弱，要说明取舍。
                5. 小周期买卖点图只展示局部交易窗口，maxCandles 必须 <= 60；同一篇报告最多 3 个 K 线图。
                6. 涉及涨跌停、炸板或封板强弱判断时必须调用 get-limit-list；股票代码统一使用带交易所后缀的 ts_code。
            """.trimIndent()
            else -> null
        }
    }

    fun approveTool(sessionId: String, requestId: String) {
        val runtimeSession =
            activeSessions[sessionId]
                ?: run {
                    logger.warning(
                        "[AgentWebSocketService] ✘ approveTool: session not found sessionId=$sessionId requestId=$requestId"
                    )
                    return
                }
        scope.launch {
            try {
                logger.info(
                    "[AgentWebSocketService] ▶ approveTool sessionId=$sessionId requestId=$requestId"
                )
                runtimeSession.runtime.bridge.sendCommand(
                    runtimeSession.acpSessionId,
                    AgentBridge.Command.Approve(requestId)
                )
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ approveTool failed sessionId=$sessionId requestId=$requestId: ${e.message}"
                )
            }
        }
    }

    fun rejectTool(sessionId: String, requestId: String) {
        val runtimeSession =
            activeSessions[sessionId]
                ?: run {
                    logger.warning(
                        "[AgentWebSocketService] ✘ rejectTool: session not found sessionId=$sessionId requestId=$requestId"
                    )
                    return
                }
        scope.launch {
            try {
                logger.info(
                    "[AgentWebSocketService] ▶ rejectTool sessionId=$sessionId requestId=$requestId"
                )
                runtimeSession.runtime.bridge.sendCommand(
                    runtimeSession.acpSessionId,
                    AgentBridge.Command.Reject(requestId)
                )
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ rejectTool failed sessionId=$sessionId requestId=$requestId: ${e.message}"
                )
            }
        }
    }

    /**
     * 在已被中断的会话上以原上下文续写。
     *
     * 设计要点：
     * - 不读取前端任何新的股票上下文，完全复用 [sessionContexts] 中已有的快照，
     *   避免上一轮因前端切换股票而被覆盖时再次错位（中国电建→华天酒店问题的兜底）。
     * - 重新把同一份 context 入队 [promptContextQueue]，让续写后的 COMPLETED 落库时仍按原股票归档。
     * - prompt 文本固定为简短的"续写指令"，不再注入 `[当前上下文]` 头部，
     *   避免续写引入新的上下文头干扰模型。
     */
    fun resumeSession(sessionId: String) {
        val runtimeSession = activeSessions[sessionId] ?: run {
            logger.warning("[AgentWebSocketService] ✘ resumeSession: session not found sessionId=$sessionId")
            return
        }
        val ctx = sessionContexts[sessionId]
        if (ctx == null) {
            logger.warning("[AgentWebSocketService] ✘ resumeSession: no prior context sessionId=$sessionId")
            scope.launch { pushError(sessionId, "无可继续的上下文，请重新发起分析") }
            return
        }
        promptContextQueue.computeIfAbsent(sessionId) { ArrayDeque() }.addLast(ctx)

        val resumePrompt = """
            继续上一次未完成的分析，直接在原上下文中续写未输出完的报告内容。
            不要重新开头、不要重复已有结论、不要再解释分析方法。
            如果先前正在进行某个小周期扫描或工具调用，请直接接续完成；
            如果报告主体已基本完成但缺少结尾，请补全剩余章节并按既定格式收尾。
        """.trimIndent()

        scope.launch {
            try {
                logger.info(
                    "[AgentWebSocketService] ▶ resumeSession sessionId=$sessionId acpSessionId=${runtimeSession.acpSessionId} ctx=$ctx"
                )
                runtimeSession.runtime.bridge.sendCommand(
                    runtimeSession.acpSessionId,
                    AgentBridge.Command.Prompt(resumePrompt)
                )
                logger.info("[AgentWebSocketService] ✔ resume prompt dispatched sessionId=$sessionId")
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ resumeSession failed sessionId=$sessionId: ${e::class.simpleName}: ${e.message}"
                )
                pushError(sessionId, "继续生成失败: ${e.message}")
            }
        }
    }

    /**
     * 中断当前正在执行的任务，保留 Session 不关闭，用户可继续会话。
     */
    fun stopSession(sessionId: String) {
        logger.info("[AgentWebSocketService] ▶ stopSession CMD received sessionId=$sessionId")
        val runtimeSession =
            activeSessions[sessionId]
                ?: run {
                    logger.warning(
                        "[AgentWebSocketService] ✘ stopSession: session not found sessionId=$sessionId"
                    )
                    return
                }
        scope.launch {
            try {
                logger.info(
                    "[AgentWebSocketService] ▶ stopSession sessionId=$sessionId"
                )
                runtimeSession.runtime.bridge.sendCommand(
                    runtimeSession.acpSessionId,
                    AgentBridge.Command.Interrupt
                )
                logger.info(
                    "[AgentWebSocketService] ✔ Interrupt command dispatched sessionId=$sessionId acpSessionId=${runtimeSession.acpSessionId}"
                )
            } catch (e: Exception) {
                logger.error(
                    "[AgentWebSocketService] ✘ stopSession failed sessionId=$sessionId: ${e.message}"
                )
            }
        }
    }

    /**
     * 把 [ClaudeUpdate] 翻译成 wire [AgentStreamPayload.Delta] 并下发。
     *
     * 高频文本增量会进入 per-session 聚合器，按当前 outbound mailbox 积压动态调整 flush
     * 间隔和批量大小；工具、审批、状态切换与终态事件会先 flush 文本再立即发送。
     */
    private suspend fun pushUpdate(
        sessionId: String,
        acpSessionId: String,
        runtime: AgentProcessRuntime,
        update: ClaudeUpdate,
        aggregator: AgentTextDeltaAggregator
    ) {
        val context = sessionContexts[sessionId]?.let {
            AgentAnalysisContext(
                tsCode = it.tsCode,
                stockName = it.stockName,
                analysisType = it.analysisType,
                tradeDate = it.tradeDate?.toString()
            )
        }
        when (update) {
            is ClaudeUpdate.OutputAppended -> {
                aggregator.append(AgentTextDeltaKind.OUTPUT, update.content, context)
                return
            }
            is ClaudeUpdate.ThinkingAppended -> {
                aggregator.append(AgentTextDeltaKind.THINKING, update.content, context)
                return
            }
            else -> aggregator.flush()
        }

        val deltaBase = AgentStreamPayload.Delta(sessionId = sessionId, context = context)
        var delta: AgentStreamPayload.Delta = when (update) {
            is ClaudeUpdate.OutputAppended,
            is ClaudeUpdate.ThinkingAppended -> return
            is ClaudeUpdate.StatusChanged -> deltaBase.copy(status = update.status)
            is ClaudeUpdate.LogAppended -> deltaBase.copy(newLogs = listOf(update.entry))
            is ClaudeUpdate.LogPatched -> deltaBase.copy(
                logPatches = listOf(
                    AgentLogPatch(
                        logId = update.logId,
                        toolInput = update.toolInput,
                        toolOutput = update.toolOutput,
                        toolStatus = update.toolStatus,
                        toolCallId = update.toolCallId
                    )
                )
            )
            is ClaudeUpdate.ActiveToolChanged -> deltaBase.copy(
                activeToolNamePresent = true,
                activeToolName = update.toolName
            )
            is ClaudeUpdate.PendingApprovalsChanged -> deltaBase.copy(
                pendingApprovals = PendingApprovalsPatch(update.ids, update.toolNames)
            )
            is ClaudeUpdate.ErrorSet -> deltaBase.copy(error = update.message)
            is ClaudeUpdate.InterruptionSet -> deltaBase.copy(interruption = update.interruption)
        }

        val statusForFraming = (update as? ClaudeUpdate.StatusChanged)?.status

        // 处理状态机迁移带来的副作用：终态时持久化 + 附带 snapshot 兜底
        val action: WsAction = when (statusForFraming) {
            AgentStatus.COMPLETED -> {
                val state = runtime.bridge.observeState(acpSessionId).value
                handleStatusTerminal(sessionId, state)
                delta = delta.copy(snapshot = buildSnapshotPayload(sessionId, state))
                WsAction.COMPLETE
            }
            AgentStatus.ERROR -> {
                val state = runtime.bridge.observeState(acpSessionId).value
                handleStatusTerminal(sessionId, state)
                delta = delta.copy(snapshot = buildSnapshotPayload(sessionId, state))
                WsAction.ERROR
            }
            else -> WsAction.UPDATE
        }

        sendAgentStreamDelta(sessionId, action, delta)
    }

    private suspend fun sendAgentStreamDelta(
        sessionId: String,
        action: WsAction,
        delta: AgentStreamPayload.Delta
    ) {
        val payload: AgentStreamPayload = delta
        AppWebSocketConnectionManager.sendToSubscribers(
            topic = WsTopic.AGENT_STREAM,
            targetId = sessionId,
            event = WsEvent(
                topic = WsTopic.AGENT_STREAM,
                action = action,
                targetId = sessionId,
                payload = json.encodeToString(payload)
            )
        )
    }

    private class AgentTextDeltaAggregator(
        private val sessionId: String,
        private val scope: CoroutineScope,
        private val sendDelta: suspend (AgentStreamPayload.Delta) -> Unit
    ) {
        private val mutex = Mutex()
        private var kind: AgentTextDeltaKind? = null
        private val buffer = StringBuilder()
        private var context: AgentAnalysisContext? = null
        private var flushJob: Job? = null

        suspend fun append(
            newKind: AgentTextDeltaKind,
            content: String,
            newContext: AgentAnalysisContext?
        ) {
            if (content.isEmpty()) return
            val deltasToSend = mutableListOf<AgentStreamPayload.Delta>()
            mutex.withLock {
                if (kind != null && kind != newKind) {
                    deltasToSend.add(buildDeltaLocked())
                    clearLocked()
                }
                kind = newKind
                context = newContext ?: context
                buffer.append(content)
                if (buffer.length >= currentPolicy().maxChars) {
                    deltasToSend.add(buildDeltaLocked())
                    clearLocked()
                } else {
                    scheduleFlushLocked()
                }
            }
            deltasToSend.forEach { sendDelta(it) }
        }

        suspend fun flush() {
            val delta = mutex.withLock {
                if (buffer.isEmpty() || kind == null) {
                    null
                } else {
                    val built = buildDeltaLocked()
                    clearLocked()
                    built
                }
            }
            delta?.let { sendDelta(it) }
        }

        fun close() {
            flushJob?.cancel()
        }

        private fun scheduleFlushLocked() {
            if (flushJob?.isActive == true) return
            val delayMs = currentPolicy().delayMs
            flushJob = scope.launch {
                delay(delayMs)
                flush()
            }
        }

        private fun buildDeltaLocked(): AgentStreamPayload.Delta {
            val content = buffer.toString()
            return when (kind) {
                AgentTextDeltaKind.OUTPUT -> AgentStreamPayload.Delta(
                    sessionId = sessionId,
                    outputAppend = content,
                    appendToLastLog = AppendToLastLog(AgentLogType.OUTPUT, content),
                    context = context
                )
                AgentTextDeltaKind.THINKING -> AgentStreamPayload.Delta(
                    sessionId = sessionId,
                    thinkingAppend = content,
                    appendToLastLog = AppendToLastLog(AgentLogType.THOUGHT, content),
                    context = context
                )
                null -> AgentStreamPayload.Delta(sessionId = sessionId, context = context)
            }
        }

        private fun clearLocked() {
            flushJob?.cancel()
            flushJob = null
            buffer.clear()
            kind = null
        }

        private fun currentPolicy(): AgentStreamFlushPolicy {
            val maxFifo = AppWebSocketConnectionManager.maxOutboundFifoSizeForTarget(sessionId)
            return when {
                maxFifo >= 768 -> AgentStreamFlushPolicy(delayMs = 700L, maxChars = 32 * 1024)
                maxFifo >= 256 -> AgentStreamFlushPolicy(delayMs = 350L, maxChars = 16 * 1024)
                maxFifo >= 64 -> AgentStreamFlushPolicy(delayMs = 150L, maxChars = 8 * 1024)
                else -> AgentStreamFlushPolicy(delayMs = 50L, maxChars = 2 * 1024)
            }
        }
    }

    /**
     * 处理状态机迁移到终态时的副作用：promptContextQueue 出队 + COMPLETED 落库。
     * 必须保持原有"首次进入 ERROR 出队 / 首次进入 COMPLETED 落库"语义。
     */
    private fun handleStatusTerminal(sessionId: String, state: ClaudeState) {
        val lastStatus = lastSessionStatus.put(sessionId, state.status)

        // 首次进入 ERROR（含 USER_CANCELLED / IDLE_TIMEOUT / MAX_TURN_REQUESTS 等可继续中断）时
        // 把队列里最早的一份 context 弹出——这一 Prompt 已经失败或被中断，不应在后续 COMPLETED 时
        // 错位匹配到下一份 context。续写时 resumeSession 会重新入队。
        if (state.status == AgentStatus.ERROR && lastStatus != AgentStatus.ERROR) {
            promptContextQueue[sessionId]?.pollFirst()
        }

        // 懒持久化：仅在首次进入 COMPLETED 终态且确实有 output 时写入数据库。
        // 中断（ERROR + interruption）一律不持久化，保证库里只存完整报告。
        if (state.status == AgentStatus.COMPLETED &&
            lastStatus != AgentStatus.COMPLETED &&
            state.output.isNotBlank()
        ) {
            val ctx = promptContextQueue[sessionId]?.pollFirst() ?: sessionContexts[sessionId]
            ctx?.let {
                try {
                    val title = buildAnalysisTitle(it, state.output)
                    val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    AgentAnalysisResultRepository.save(
                        AgentAnalysisResultModel(
                            id = UUID.randomUUID(),
                            userId = it.userId,
                            tsCode = it.tsCode ?: "",
                            analysisType = it.analysisType ?: AgentAnalysisType.GENERAL.code,
                            sessionId = sessionId,
                            title = title,
                            contentMd = state.output,
                            metadataJson = null,
                            tradeDate = it.tradeDate?.let { d -> kotlinx.datetime.LocalDate(d.year, d.monthValue, d.dayOfMonth) },
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    logger.info("[AgentWebSocketService] ✔ Analysis result persisted for sessionId=$sessionId")
                } catch (e: Exception) {
                    logger.error(
                        "[AgentWebSocketService] ✘ Failed to persist analysis result sessionId=$sessionId: ${e.message}"
                    )
                }
            } ?: logger.warning("[AgentWebSocketService] Missing context for sessionId=$sessionId, skip persistence")
        }
    }

    private fun buildSnapshotPayload(sessionId: String, state: ClaudeState): AgentStatePayload {
        val context = sessionContexts[sessionId]?.let {
            AgentAnalysisContext(
                tsCode = it.tsCode,
                stockName = it.stockName,
                analysisType = it.analysisType,
                tradeDate = it.tradeDate?.toString()
            )
        }
        return AgentStatePayload(
            sessionId = sessionId,
            status = state.status,
            thinking = state.thinking,
            output = state.output,
            activeToolName = state.activeTool?.name,
            pendingApprovalIds = state.pendingApprovals.map { it.id },
            pendingApprovalTools = state.pendingApprovals.map { it.toolName },
            error = state.error,
            logs = state.logs,
            context = context,
            interruption = state.interruption
        )
    }

    /**
     * 由 [AppWebSocketConnectionManager] 在 SUBSCRIBE_AGENT 时调用。
     *
     * 两个场景共用：
     * - 用户首次进入会话页面：把当前累积状态一次性给前端，避免空 UI
     * - 断线后客户端重连恢复订阅：让前端拿到 baseline，后续 Delta 重新接上
     */
    suspend fun sendInitialSnapshot(
        session: DefaultWebSocketServerSession,
        sessionId: String
    ) {
        val runtimeSession = activeSessions[sessionId] ?: run {
            logger.warning(
                "[AgentWebSocketService] sendInitialSnapshot: session not found sessionId=$sessionId"
            )
            return
        }
        val state = runtimeSession.runtime.bridge.observeState(runtimeSession.acpSessionId).value
        val snapshot = buildSnapshotPayload(sessionId, state)
        val payload: AgentStreamPayload = AgentStreamPayload.Snapshot(snapshot)
        AppWebSocketConnectionManager.sendToSession(
            session = session,
            event = WsEvent(
                topic = WsTopic.AGENT_STREAM,
                action = WsAction.SYNC,
                targetId = sessionId,
                payload = json.encodeToString(payload)
            )
        )
    }

    private suspend fun pushSessionError(
        webSocketSession: DefaultWebSocketServerSession,
        sessionId: String,
        errorMessage: String
    ) {
        val event =
            WsEvent(
                topic = WsTopic.AGENT_SESSION,
                action = WsAction.ERROR,
                targetId = sessionId,
                payload = errorMessage
            )
        AppWebSocketConnectionManager.sendToSession(webSocketSession, event)
    }

    private fun buildAnalysisTitle(ctx: SessionContext, output: String): String? {
        val stockName = ctx.stockName?.trim()?.takeIf { it.isNotBlank() }
        val analysisType = ctx.analysisType
            ?.let { code -> AgentAnalysisType.entries.find { it.code == code } }

        if (stockName != null && analysisType != null) {
            val reportName = when (analysisType) {
                AgentAnalysisType.ENTRY_EXIT -> "买卖点分析报告"
                AgentAnalysisType.RESEARCH_REPORT -> "研报分析报告"
                else -> "${analysisType.displayName}报告"
            }
            return "$stockName：$reportName".take(256)
        }

        return output.lineSequence()
            .map { it.trimStart('#', ' ', '\t').trim() }
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.take(256)
    }

    private suspend fun pushError(sessionId: String, errorMessage: String) {
        // sendPrompt / approveTool 等命令在转发前就失败（如 session 找不到、payload 解析失败）时
        // 走这条路径。它不经过 StateManager，所以这里直接合成一份只含 error 的终态 Delta，
        // 并尽可能附带当前 snapshot（如果会话仍然存在）让前端做对账。
        val runtimeSession = activeSessions[sessionId]
        val snapshot = runtimeSession?.let {
            val state = it.runtime.bridge.observeState(it.acpSessionId).value
            buildSnapshotPayload(sessionId, state)
        }
        val context = sessionContexts[sessionId]?.let {
            AgentAnalysisContext(
                tsCode = it.tsCode,
                stockName = it.stockName,
                analysisType = it.analysisType,
                tradeDate = it.tradeDate?.toString()
            )
        }
        val payload: AgentStreamPayload = AgentStreamPayload.Delta(
            sessionId = sessionId,
            status = AgentStatus.ERROR,
            error = errorMessage,
            context = context,
            snapshot = snapshot
        )
        AppWebSocketConnectionManager.sendToSubscribers(
            topic = WsTopic.AGENT_STREAM,
            targetId = sessionId,
            event =
                WsEvent(
                    topic = WsTopic.AGENT_STREAM,
                    action = WsAction.ERROR,
                    targetId = sessionId,
                    payload = json.encodeToString(payload)
                )
        )
    }
}

package org.shiroumi.agententry

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import model.ws.AgentStatus
import org.shiroumi.agent.api.AgentBridge
import org.shiroumi.agent.impl.AgentBridgeImpl
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.DecisionFile
import org.shiroumi.backtest.feed.DecisionFileJson
import org.shiroumi.config.AgentModelResolution

/**
 * 单只股票 agent 量价买点分析的共享内核——回测对照（写文件）与生产回填（写 DB）两条链路共用。
 *
 * 每只票进程级隔离：独立 workDir（`{base}/{信号日}/{ts}/`）+ 独立 as-of wrapper（--as-of 写死信号日，
 * agent 看不到信号日之后数据，杜绝未来函数）+ 独立 ACP 进程。失败重试一次，单只失败不影响其他票。
 *
 * 产物：agent 把买点写到 `{workDir}/out/decisions/{执行日}-{ts}.json`；本内核据该文件是否产出判定成败，
 * 并提供 [parseBuyPoint] 把文件解析为 limitPrice（与 [org.shiroumi.backtest.feed.AgentEntryPriceFeed] 口径一致）。
 */
object AgentEntryPriceAnalyzer {

    /** ACP launch(initialize 握手)硬超时:沙箱真开 + 冷启动握手卡死的兜底上界,与 USER 档一致。 */
    private const val LAUNCH_TIMEOUT_MS = 30_000L
    /** ACP createSession(newSession)硬超时:单 attempt 卡死上界,与 USER 档一致。 */
    private const val CREATE_SESSION_TIMEOUT_MS = 10_000L

    /** 单只分析任务：信号日（as-of 锚）、执行日（产物命名/回填键）、股票代码。 */
    data class StockTask(
        val signalDate: LocalDate,
        val executionDate: LocalDate,
        val tsCode: String,
    )

    /** 单只分析结果：成功时携带产物文件，供下游解析买点或合并落盘。 */
    data class StockResult(
        val signalDate: LocalDate,
        val tsCode: String,
        val ok: Boolean,
        val perStockFile: File?,
    )

    /**
     * 驱动 agent 分析单只股票，失败重试 1 次。
     *
     * @param onLog 进度回显回调（命令侧注入 echo），内核不直接打印。
     */
    suspend fun analyzeOneStock(
        projectRoot: File,
        workspaceBase: File,
        serverPort: Int,
        model: AgentModelResolution.Resolved,
        task: StockTask,
        perStockTimeoutSec: Long,
        onLog: (String) -> Unit = {},
    ): StockResult {
        val signalDate = task.signalDate
        val executionDate = task.executionDate
        val tsCode = task.tsCode

        val signalDateYyyymmdd = signalDate.toString().replace("-", "")
        val workDir = File(File(workspaceBase, signalDate.toString()), tsCode)
        val provision = BatchAgentProvisioning.provision(
            projectRoot = projectRoot,
            workDir = workDir,
            signalDateYyyymmdd = signalDateYyyymmdd,
            serverPort = serverPort,
        )
        if (provision.missingTools.isNotEmpty()) {
            onLog("⚠ $tsCode 缺失工具 ${provision.missingTools}（先 installDist）")
        }
        val decisionsDir = File(workDir, "out/decisions")
        val perStockFile = File(decisionsDir, "$executionDate-$tsCode.json")

        val bridge = AgentBridgeImpl()
        try {
            // BACKFILL 档同走 sandbox-exec,launch(initialize 握手)与 createSession(newSession)在沙箱真开 +
            // 冷启动下可能永久阻塞。盘后无人值守批量回填里单票卡死会无限占线程/句柄、拖死整批并泄漏进程。
            // 补硬超时使单票卡死有界:launch 超时→本票启动失败(走最终 false);createSession 超时→本 attempt
            // 失败进下一次重试。guard 因 BACKFILL 批量超时 TRIP 后,后续票自动 OFF 裸跑,保住回填覆盖率。
            val launched = withTimeoutOrNull(LAUNCH_TIMEOUT_MS) {
                bridge.launch(
                    AgentBridge.Config(
                        workDir = workDir.absolutePath,
                        claudeCommand = null,
                        isolated = true,
                        backtestMode = true, // 回测命令白名单：禁日期参数 / 禁 market-emotion / 禁研报
                        sandboxTier = org.shiroumi.agent.acp.SandboxTier.BACKFILL, // OS 层沙箱回填档：禁实时外网
                        preferZedAcpAgent = true,
                        apiKey = model.apiKey,
                        configDir = null,
                        modelId = model.modelId,
                        baseUrl = model.baseUrl,
                        provider = model.provider,
                    )
                )
                true
            }
            if (launched == null) {
                org.shiroumi.agent.acp.SandboxRolloutGuard.recordTimeout(org.shiroumi.agent.acp.SandboxTier.BACKFILL)
                onLog("✘ $tsCode launch 超时(${LAUNCH_TIMEOUT_MS}ms)，本票启动失败")
                return StockResult(signalDate, tsCode, ok = false, perStockFile = null)
            }
            org.shiroumi.agent.acp.SandboxRolloutGuard.recordSuccess(org.shiroumi.agent.acp.SandboxTier.BACKFILL)
            repeat(2) { attempt ->
                try {
                    perStockFile.delete() // 重试前清残留，确保产出判定干净
                    val sessionId = withTimeoutOrNull(CREATE_SESSION_TIMEOUT_MS) {
                        bridge.createSession(workDir.absolutePath)
                    }
                    if (sessionId == null) {
                        org.shiroumi.agent.acp.SandboxRolloutGuard.recordTimeout(org.shiroumi.agent.acp.SandboxTier.BACKFILL)
                        onLog("✘ $tsCode 第 ${attempt + 1} 次 createSession 超时(${CREATE_SESSION_TIMEOUT_MS}ms)")
                        return@repeat
                    }
                    try {
                        bridge.sendCommand(
                            sessionId,
                            AgentBridge.Command.Prompt(buildPrompt(tsCode, signalDate, executionDate)),
                        )
                        val finalState = withTimeoutOrNull(perStockTimeoutSec * 1000L) {
                            bridge.observeState(sessionId).first {
                                it.status == AgentStatus.COMPLETED || it.status == AgentStatus.ERROR
                            }
                        }
                        val produced = perStockFile.exists() && perStockFile.length() > 0L
                        if (finalState != null && finalState.status != AgentStatus.ERROR && produced) {
                            return StockResult(signalDate, tsCode, ok = true, perStockFile = perStockFile)
                        }
                        onLog(
                            "✘ $tsCode 第 ${attempt + 1} 次失败：" +
                                "status=${finalState?.status ?: "TIMEOUT"} 产出=$produced"
                        )
                    } finally {
                        bridge.closeSession(sessionId)
                    }
                } catch (e: Exception) {
                    onLog("✘ $tsCode 第 ${attempt + 1} 次异常：${e.message}")
                }
            }
        } finally {
            bridge.shutdown() // 关掉本票独立 ACP 进程，释放句柄/内存
        }
        return StockResult(signalDate, tsCode, ok = false, perStockFile = null)
    }

    /** 单只股票分析 Prompt。绝不暴露执行日之后信息；执行日仅用于命名产物文件。 */
    fun buildPrompt(tsCode: String, signalDate: LocalDate, executionDate: LocalDate): String =
        "分析股票 $tsCode 在信号日 $signalDate 盘后的主推买点入场价（纯历史 K 线结构，as-of 已锁定为信号日）。" +
            "分析完成后把买点写到 out/decisions/$executionDate-$tsCode.json，" +
            "executionDate 与 effectiveDate 都填执行日 $executionDate，side=BUY，hint=LIMIT，limitPrice 必填。"

    /**
     * 从单票产物文件解析 agent 买点限价。
     *
     * 只采纳 side=BUY、hint=LIMIT、limitPrice 非空的首个 [StrategyDecision.TradeIntentDecision]，
     * 与 [org.shiroumi.backtest.feed.AgentEntryPriceFeed] 口径一致。无有效买点返回 null。
     */
    fun parseBuyPoint(file: File): Double? {
        if (!file.exists() || file.length() == 0L) return null
        val decoded = DecisionFileJson.decodeFromString<DecisionFile>(file.readText())
        for (decision in decoded.decisions) {
            if (decision !is StrategyDecision.TradeIntentDecision) continue
            if (decision.side != Side.BUY) continue
            if (decision.hint != ExecutionHint.LIMIT) continue
            val price = decision.limitPrice ?: continue
            return price
        }
        return null
    }
}

package org.shiroumi.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.encodeToString
import org.shiroumi.agent.api.AgentBridge
import org.shiroumi.agent.impl.AgentBridgeImpl
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.DecisionFile
import org.shiroumi.backtest.feed.DecisionFileJson
import org.shiroumi.cli.batch.BatchAgentProvisioning
import org.shiroumi.config.AgentModelResolution
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import model.ws.AgentStatus

/**
 * `./cli batch-agent-driver` —— 并发驱动回测 agent 为每日选股逐只产出买点价 JSON。
 *
 * 业务链路定位（防未来函数为第一原则）：
 *  1. 入参给定信号日区间（库里的 target_date，也是回测引擎的执行日键）。从 daily_profit_prediction_selection
 *     取每个信号日 Top N 已选股票。
 *  2. 对每个信号日 T：为该信号日布一个隔离回测 agent 工作空间，wrapper 把历史取数工具的 --as-of 写死为
 *     信号日 T，agent 看不到 T 之后的任何数据。
 *  3. 并发（限流到 --parallelism）对该信号日每只票发 Prompt，agent 把单票买点写到 out/decisions/{T}-{ts}.json。
 *  4. 宿主把同一信号日的各单票文件合并为单个 {T}.json，写到统一 --agent-entry-prices 目录，
 *     供后续 `./cli backtest run --matching LIMIT --agent-entry-prices {dir}` 按执行日键读取限价买点撮合。
 *
 * 字段方向（与回测引擎口径严格对齐，杜绝错位）：
 *  - 库 daily_profit_prediction_selection.target_date = 信号确认日，也是回测引擎推进的执行日键：
 *    PreloadedDecisionFeed.fromDatabase 把 --start/--end 区间交易日直接当 target_date 查，effectiveDate=target_date。
 *  - 产物文件名 = {target_date}.json（= signalDate，绝不 +1）；AgentEntryPriceFeed.entryPricesFor(date) 按同一
 *    target_date 读 {date}.json。两端日期键必须一致，否则 LIMIT 回测读不到买点 → 全放弃入场。
 *  - agent 取数 --as-of = 信号日 target_date（YYYYMMDD）；T+1 撮合语义由引擎用次日开盘价成交隐含，不进日期键。
 *  - 库 trade_date 列本驱动与回测引擎均不作执行日键，不依赖。
 *
 * 边界纪律：cli 不依赖 ktor-server。隔离装配逻辑在 [BatchAgentProvisioning]（cli 侧等价落地，与生产同口径）。
 * 历史取数 *-asof 工具走 HTTP 回连 Ktor server，因此运行本驱动前必须先启动 server（端口取 config.yaml）。
 */
class BatchAgentDriverCommand : CliktCommand(
    name = "batch-agent-driver",
    help = "并发驱动回测 agent 为信号日区间内每日选股逐只产出买点价，合并为 {执行日}.json 供 LIMIT 回测消费。"
) {

    private val startSignalDate by option(
        "--start",
        help = "起始信号日 target_date（YYYY-MM-DD），库里的盘后选出日。与 --recent-trading-days 二选一。",
    )

    private val endSignalDate by option(
        "--end",
        help = "结束信号日 target_date（YYYY-MM-DD），含端点",
    ).required()

    private val recentTradingDays by option(
        "--recent-trading-days",
        help = "取截至 --end 的最近 N 个开市交易日作为信号日区间（按交易日历精确裁剪）。与 --start 二选一。",
    ).int()

    private val topN by option(
        "--top-n",
        help = "每个信号日取模型分最高的前 N 只已选股票（默认 5）",
    ).int().default(5)

    private val parallelism by option(
        "--parallelism",
        help = "全局并发分析的股票数。默认 0 = 自动取 CPU 核心数（Runtime.availableProcessors）。",
    ).int().default(0)

    private val outputDir by option(
        "--agent-entry-prices",
        help = "买点价产物目录：写 {执行日}.json，供 `backtest run --agent-entry-prices` 消费。" +
            "默认 ./agent-entry-prices/{runId}/",
    ).path()

    private val workspaceBaseDir by option(
        "--workspace-base",
        help = "agent 工作空间基目录，默认 ~/.quant_backtest_agents/",
    ).path()

    private val perStockTimeoutSec by option(
        "--timeout-sec",
        help = "单只股票单次分析超时（秒），默认 300。超时记为失败并触发一次重试。",
    ).long().default(300L)

    private val modelKey by option(
        "--model-key",
        help = "选用 config.yaml agent.modelPresets 中的预设 key；不指定则用 agent.defaultModelKey / 顶层 modelId。",
    )

    private val skipExisting by option(
        "--skip-existing",
        help = "断点续跑：跳过 --agent-entry-prices 目录下 {执行日}.json 已含买点的票，只补缺失的票。" +
            "合并时保留已有买点（已成功的票不重跑、不覆盖）。",
    ).flag(default = false)

    override fun run() {
        val quantConfig = ConfigManager.load()
        val serverPort = quantConfig.server.port
        val model = AgentModelResolution.resolve(quantConfig.agent, modelKey)

        // 并发度：默认 0 -> 自动取 CPU 核心数；显式正数则照用。全局并发池统一限流，
        // 跨信号日所有股票任务共享这一个信号量（不再逐信号日串行）。
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val effectiveParallelism = if (parallelism <= 0) cpuCores else parallelism

        val signalEnd = LocalDate.parse(endSignalDate)
        require((startSignalDate != null) != (recentTradingDays != null)) {
            "--start 与 --recent-trading-days 必须二选一（不可同时提供，也不可都不提供）"
        }

        // 仅取开市信号日，避免对非交易日发起无意义会话。
        val signalDates: List<LocalDate> = if (recentTradingDays != null) {
            val n = recentTradingDays!!
            require(n > 0) { "--recent-trading-days 必须为正整数：$n" }
            // 起点设宽（按 N 个交易日 ×2 自然日兜底回看），再取交易日历裁出的最后 N 个开市日。
            val lookbackStart = signalEnd.minus(DatePeriod(days = n * 2 + 10))
            val open = TradingCalendarRepository.findOpenDates(lookbackStart, signalEnd)
            require(open.isNotEmpty()) { "回看区间内没有开市日：$lookbackStart ~ $signalEnd" }
            require(open.size >= n) { "回看区间开市日不足 $n 个（仅 ${open.size} 个），请扩大 --end 之前的数据范围" }
            open.takeLast(n)
        } else {
            val signalStart = LocalDate.parse(startSignalDate!!)
            require(signalStart <= signalEnd) { "起始信号日不能晚于结束信号日：$signalStart > $signalEnd" }
            TradingCalendarRepository.findOpenDates(signalStart, signalEnd)
                .also { require(it.isNotEmpty()) { "信号日区间内没有开市日：$signalStart ~ $signalEnd" } }
        }
        val signalStart = signalDates.first()

        val runId = "batch-${System.currentTimeMillis()}"
        val resolvedOutputDir: Path = (outputDir ?: Paths.get("agent-entry-prices", runId)).toAbsolutePath()
        File(resolvedOutputDir.toString()).mkdirs()
        val workspaceBase: File = (workspaceBaseDir?.toFile()
            ?: File(System.getProperty("user.home"), ".quant_backtest_agents"))
            .let { File(it, runId) }
        val projectRoot = File(System.getProperty("quant.project.root") ?: System.getProperty("user.dir"))

        echo("[batch] runId=$runId")
        echo(
            "[batch] 信号日区间=$signalStart ~ $signalEnd（开市 ${signalDates.size} 日）, topN=$topN, " +
                "全局并发=$effectiveParallelism" +
                if (parallelism <= 0) "（自动=CPU $cpuCores 核）" else "（手动指定）"
        )
        echo("[batch] 模型=${model.modelId ?: "默认"} provider=${model.provider} server端口=$serverPort")
        echo("[batch] 买点价产物目录=$resolvedOutputDir")
        echo("[batch] 工作空间基目录=$workspaceBase")

        // 批量预取每个信号日的 Top N 已选股票，单次查询替代逐日 N 次。
        val selectionsBySignalDate = DailyProfitPredictionSelectionRepository
            .findSelectionsByTargetDates(signalDates)

        // 断点续跑：读取输出目录已有 {执行日}.json 中已成功的 (执行日, 票) 集合，用于跳过。
        val existingByDate: Map<LocalDate, Set<String>> = if (skipExisting) {
            loadExistingBuyPoints(resolvedOutputDir)
        } else {
            emptyMap()
        }
        if (skipExisting) {
            val total = existingByDate.values.sumOf { it.size }
            echo("[batch] 断点续跑：已有买点 $total 只（${existingByDate.size} 个执行日），将跳过这些票只补缺失")
        }

        // 摊平所有 (信号日, 股票) 为全局任务单元——并发粒度 = 单只股票。
        // 执行日 = 库 target_date 本身（= signalDate）。回测引擎 PreloadedDecisionFeed.fromDatabase
        // 把 --start/--end 区间交易日直接当 target_date 查（findTargetsByTargetDates），
        // effectiveDate=target_date；EntryGatekeeper / AgentEntryPriceFeed 也按同一 target_date 读
        // {date}.json。因此 driver 产物文件名必须 = target_date，不能再 +1 个交易日，否则与回测引擎
        // 决策日期键错位，LIMIT 回测读不到买点 → 全放弃入场。as-of 取到 signalDate 当天为止的历史。
        val tasks: List<StockTask> = signalDates.flatMap { signalDate ->
            val topPicks = selectionsBySignalDate[signalDate].orEmpty().take(topN)
            if (topPicks.isEmpty()) {
                echo("[batch] 信号日 $signalDate 无已选股票，跳过")
                emptyList()
            } else {
                val done = existingByDate[signalDate].orEmpty()
                topPicks
                    .filter { it.tsCode !in done }
                    .map { StockTask(signalDate = signalDate, executionDate = signalDate, tsCode = it.tsCode) }
            }
        }
        echo(
            "[batch] 待跑任务=${tasks.size} 只（跨信号日摊平" +
                (if (skipExisting) "，已跳过 ${existingByDate.values.sumOf { it.size }} 只已成功" else "") +
                "），并发上限=$effectiveParallelism（每只票独立进程）"
        )

        // 全局并发池：单只股票 = 一个任务 = 一个独立 workDir + 独立 bridge + 独立 ACP 进程。
        // 一个全局信号量统一限流到 effectiveParallelism，跨信号日真正并行，峰值 = 核心数个 ACP 进程。
        val results: List<StockResult> = runBlocking {
            val gate = Semaphore(effectiveParallelism)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            tasks.map { task ->
                scope.async {
                    gate.withPermit {
                        analyzeOneStock(
                            projectRoot = projectRoot,
                            workspaceBase = workspaceBase,
                            serverPort = serverPort,
                            model = model,
                            task = task,
                        )
                    }
                }
            }.awaitAll()
        }

        // 按信号日归并各单票买点为 {执行日}.json，并算覆盖率。
        // 续跑模式下合并时叠加已有买点（已成功的票不覆盖），保证完全成功日原样保留、部分成功日不丢已有票。
        val coverageRows: List<SignalDateCoverage> = results
            .groupBy { it.signalDate }
            .toSortedMap()
            .map { (signalDate, dayResults) ->
                finalizeSignalDay(signalDate, dayResults, resolvedOutputDir, mergeExisting = skipExisting)
            }

        printCoverageReport(coverageRows)
    }

    /** 读取输出目录已有 {执行日}.json 中已成功的 (执行日 -> 票集合)，用于断点续跑跳过。 */
    private fun loadExistingBuyPoints(outputDir: Path): Map<LocalDate, Set<String>> {
        val dir = File(outputDir.toString())
        val result = LinkedHashMap<LocalDate, Set<String>>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            runCatching {
                val decoded = DecisionFileJson.decodeFromString<DecisionFile>(file.readText())
                val codes = decoded.decisions
                    .mapNotNull { (it as? StrategyDecision.TradeIntentDecision) }
                    .filter { it.side == Side.BUY && it.hint == ExecutionHint.LIMIT && it.limitPrice != null }
                    .map { it.tsCode }
                    .toSet()
                if (codes.isNotEmpty()) result[decoded.executionDate] = codes
            }
        }
        return result
    }

    /** 全局并发任务单元：单只股票。signalDate=executionDate=库 target_date。 */
    private class StockTask(
        val signalDate: LocalDate,
        val executionDate: LocalDate,
        val tsCode: String,
    )

    /**
     * 阶段二：合并某信号日各单票产物为 {执行日}.json，算覆盖率。在该信号日全部股票任务跑完后调用。
     *
     * 单票产物落各自独立 workDir 的 out/decisions/{执行日}-{ts}.json（进程级隔离，互不写同一文件），
     * 这里按 StockResult 携带的产物路径回收，合并去重保留首个 BUY/LIMIT 买点价。
     */
    private fun finalizeSignalDay(
        signalDate: LocalDate,
        dayResults: List<StockResult>,
        outputDir: Path,
        mergeExisting: Boolean = false,
    ): SignalDateCoverage {
        val executionDate = signalDate
        val outFile = File(outputDir.toString(), "$executionDate.json")
        // 续跑：把已有产物文件排在最前，mergeDecisionFiles 用 putIfAbsent 保留已有买点，新跑的票补缺。
        val sourceFiles = buildList {
            if (mergeExisting && outFile.isFile) add(outFile)
            addAll(dayResults.mapNotNull { it.perStockFile })
        }
        val merged = mergeDecisionFiles(executionDate, sourceFiles)
        outFile.writeText(DecisionFileJson.encodeToString(merged))

        val covered = merged.decisions.mapNotNull { (it as? StrategyDecision.TradeIntentDecision)?.tsCode }.toSet()
        val requested = dayResults.map { it.tsCode }
        val missing = requested.filter { it !in covered }
        echo(
            "[batch] 信号日 $signalDate 完成：买点 ${covered.size}/${requested.size} -> ${outFile.absolutePath}" +
                if (missing.isNotEmpty()) " | 缺买点 $missing" else ""
        )

        return SignalDateCoverage(
            signalDate = signalDate,
            executionDate = executionDate,
            requested = requested.size,
            covered = covered.size,
            missing = missing,
            failures = dayResults.filter { !it.ok }.map { it.tsCode },
        )
    }

    /**
     * 驱动 agent 分析单只股票，失败重试 1 次。
     *
     * agent 完成后会把买点写到 out/decisions/{执行日}-{ts}.json；本函数据该文件是否产出判定单只成败。
     * 单只失败不影响其他股票（每只独立 session + try-catch + 重试）。
     */
    private suspend fun analyzeOneStock(
        projectRoot: File,
        workspaceBase: File,
        serverPort: Int,
        model: AgentModelResolution.Resolved,
        task: StockTask,
    ): StockResult {
        val signalDate = task.signalDate
        val executionDate = task.executionDate
        val tsCode = task.tsCode

        // 进程级隔离：每只票一个独立 workDir（{base}/{信号日}/{ts}/）+ 独立 as-of wrapper + 独立 ACP 进程。
        // 不同票互不共享 .claude-isolated / skills，无并发写竞态；as-of 各自写死该信号日，互不污染。
        val signalDateYyyymmdd = signalDate.toString().replace("-", "")
        val workDir = File(File(workspaceBase, signalDate.toString()), tsCode)
        val provision = BatchAgentProvisioning.provision(
            projectRoot = projectRoot,
            workDir = workDir,
            signalDateYyyymmdd = signalDateYyyymmdd,
            serverPort = serverPort,
        )
        if (provision.missingTools.isNotEmpty()) {
            echo("[batch]   ⚠ $tsCode 缺失工具 ${provision.missingTools}（先 installDist）")
        }
        val decisionsDir = File(workDir, "out/decisions")
        val perStockFile = File(decisionsDir, "$executionDate-$tsCode.json")

        val bridge = AgentBridgeImpl()
        try {
            bridge.launch(
                AgentBridge.Config(
                    workDir = workDir.absolutePath,
                    claudeCommand = null,
                    isolated = true,
                    backtestMode = true, // 启用回测命令白名单：禁日期参数 / 禁 market-emotion / 禁研报
                    preferZedAcpAgent = true,
                    apiKey = model.apiKey,
                    configDir = null,
                    modelId = model.modelId,
                    baseUrl = model.baseUrl,
                    provider = model.provider,
                )
            )
            repeat(2) { attempt ->
                try {
                    perStockFile.delete() // 重试前清残留，确保产出判定干净
                    val sessionId = bridge.createSession(workDir.absolutePath)
                    try {
                        bridge.sendCommand(
                            sessionId,
                            AgentBridge.Command.Prompt(buildPrompt(tsCode, signalDate, executionDate)),
                        )
                        val finalState = withTimeoutOrNull(perStockTimeoutSec * 1000L) {
                            // 只认真正的终态 COMPLETED / ERROR：初始 IDLE 不算（否则 prompt 还没起跑就误判完成）。
                            bridge.observeState(sessionId).first {
                                it.status == AgentStatus.COMPLETED || it.status == AgentStatus.ERROR
                            }
                        }
                        val produced = perStockFile.exists() && perStockFile.length() > 0L
                        if (finalState != null && finalState.status != AgentStatus.ERROR && produced) {
                            return StockResult(signalDate, tsCode, ok = true, perStockFile = perStockFile)
                        }
                        echo(
                            "[batch]   ✘ $tsCode 第 ${attempt + 1} 次失败：" +
                                "status=${finalState?.status ?: "TIMEOUT"} 产出=$produced"
                        )
                    } finally {
                        bridge.closeSession(sessionId)
                    }
                } catch (e: Exception) {
                    echo("[batch]   ✘ $tsCode 第 ${attempt + 1} 次异常：${e.message}")
                }
            }
        } finally {
            bridge.shutdown() // 关掉本票独立 ACP 进程，释放句柄/内存
        }
        return StockResult(signalDate, tsCode, ok = false, perStockFile = null)
    }

    /** 单只股票分析 Prompt。绝不暴露执行日之后信息；执行日仅用于命名产物文件。 */
    private fun buildPrompt(tsCode: String, signalDate: LocalDate, executionDate: LocalDate): String =
        "分析股票 $tsCode 在信号日 $signalDate 盘后的主推买点入场价（纯历史 K 线结构，as-of 已锁定为信号日）。" +
            "分析完成后把买点写到 out/decisions/$executionDate-$tsCode.json，" +
            "executionDate 与 effectiveDate 都填执行日 $executionDate，side=BUY，hint=LIMIT，limitPrice 必填。"

    /**
     * 合并若干单票产物文件为单个 [DecisionFile]。单票产物分散在各自独立 workDir，由 StockResult 携带路径回收。
     *
     * 只采纳 BUY/LIMIT/limitPrice 非空的 TradeIntentDecision，同标的保留首个，effectiveDate 统一收敛到执行日。
     */
    private fun mergeDecisionFiles(executionDate: LocalDate, files: List<File>): DecisionFile {
        val merged = LinkedHashMap<String, StrategyDecision.TradeIntentDecision>()
        files.filter { it.isFile && it.length() > 0L }.sortedBy { it.name }.forEach { file ->
            runCatching {
                val decoded = DecisionFileJson.decodeFromString<DecisionFile>(file.readText())
                decoded.decisions.forEach { decision ->
                    if (decision is StrategyDecision.TradeIntentDecision &&
                        decision.side == Side.BUY &&
                        decision.hint == ExecutionHint.LIMIT &&
                        decision.limitPrice != null
                    ) {
                        merged.putIfAbsent(decision.tsCode, decision.copy(effectiveDate = executionDate))
                    }
                }
            }.onFailure { echo("[batch]   ⚠ 解析单票产物失败 ${file.name}：${it.message}") }
        }
        return DecisionFile(executionDate = executionDate, decisions = merged.values.toList())
    }

    /** 打印整体覆盖率与缺失清单（应≈100%）。 */
    private fun printCoverageReport(rows: List<SignalDateCoverage>) {
        echo("")
        echo("================ batch-agent-driver 覆盖率报告 ================")
        if (rows.isEmpty()) {
            echo("没有任何信号日被处理。")
            return
        }
        val totalReq = rows.sumOf { it.requested }
        val totalCov = rows.sumOf { it.covered }
        rows.forEach { row ->
            echo(
                "信号日 ${row.signalDate} -> 执行日 ${row.executionDate}：" +
                    "买点 ${row.covered}/${row.requested}" +
                    (if (row.missing.isNotEmpty()) " | 缺买点 ${row.missing}" else "") +
                    (if (row.failures.isNotEmpty()) " | 失败 ${row.failures}" else "")
            )
        }
        val pct = if (totalReq == 0) "0.00" else {
            val v = totalCov.toDouble() / totalReq.toDouble() * 100.0
            (kotlin.math.round(v * 100.0) / 100.0).toString()
        }
        echo("------------------------------------------------------------")
        echo("总覆盖率：$totalCov/$totalReq = $pct%")
        echo("============================================================")
    }

    /** 单只股票分析结果。perStockFile 为成功时的单票产物文件，失败为 null。 */
    private data class StockResult(
        val signalDate: LocalDate,
        val tsCode: String,
        val ok: Boolean,
        val perStockFile: File?,
    )

    /** 单信号日覆盖率明细。 */
    private data class SignalDateCoverage(
        val signalDate: LocalDate,
        val executionDate: LocalDate,
        val requested: Int,
        val covered: Int,
        val missing: List<String>,
        val failures: List<String>,
    )
}

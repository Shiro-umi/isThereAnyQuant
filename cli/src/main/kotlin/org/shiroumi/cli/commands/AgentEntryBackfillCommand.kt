package org.shiroumi.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import org.shiroumi.agententry.AgentEntryBackfiller
import org.shiroumi.agententry.AgentEntryPriceAnalyzer
import org.shiroumi.config.AgentModelResolution
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository

/**
 * `./cli agent-entry-backfill` —— 生产选股链路的买点回填步骤。
 *
 * 业务定位：选股出 Top 后，对当日 selected 票并发跑 agent 量价分析产出买点限价，回填到
 * daily_profit_prediction_selection.limit_price 列。持仓状态机入场时读该列走 LIMIT 触达建仓
 * （[org.shiroumi.strategy.service.postmarket.HoldingStateMachine.EntryCandidate.entryLimitPrice]），
 * 缺买点的票回退开盘价无条件建仓。
 *
 * 与 [BatchAgentDriverCommand]（回测对照，写 {date}.json 文件）的区别：本命令是生产链路常驻一环，
 * 单日、按数据库 target_date 取 selected 票、产物只回填 DB limit_price 列（不落文件）。两者共用同一
 * 并发 agent 分析内核 [AgentEntryPriceAnalyzer]（进程级隔离 + as-of 防未来函数 + 失败重试一次）。
 *
 * 防未来函数：买点的 as-of 锚 = 信号日（target_date 的上一交易日），agent 看不到信号日之后数据。
 *
 * 边界纪律：cli 不依赖 ktor-server；历史取数 *-asof 工具走 HTTP 回连 Ktor server，运行前须先启动 server。
 * agent provider 默认走 config.yaml agent.defaultModelKey（生产配 deepseek），可用 --model-key 覆盖。
 *
 * 运行：./cli agent-entry-backfill --target-date 2026-06-18 [--parallelism 0] [--model-key deepseek-v4-flash]
 */
class AgentEntryBackfillCommand : CliktCommand(
    name = "agent-entry-backfill",
    help = "选股出 Top 后并发跑 agent 为当日 selected 票产出买点限价，回填 daily_profit_prediction_selection.limit_price。"
) {
    private val targetDateOpt by option(
        "--target-date",
        help = "执行日（库 target_date，= 选股生效买入日 T+1）。回填该日 selected 票的买点。",
    ).required()

    private val topN by option("--top-n", help = "每日取模型分前 N 只回填买点，默认 5（回填全部 selected 票，与盘后自动回填同口径）。")
        .int().default(5)

    private val parallelism by option(
        "--parallelism",
        help = "并发上限。默认 0 = 自动取 CPU 核心数；有几个 Top 就并发几个 agent（受此上限约束）。",
    ).int().default(0)

    private val perStockTimeoutSec by option(
        "--per-stock-timeout-sec",
        help = "单只股票单次分析超时（秒），默认 300。超时记为失败并触发一次重试。",
    ).long().default(300)

    private val modelKey by option(
        "--model-key",
        help = "选用 config.yaml agent.modelPresets 中的预设 key；不指定则用 agent.defaultModelKey（生产配 deepseek）。",
    )

    private val workspaceBaseDir by option(
        "--workspace-base",
        help = "agent 隔离工作空间基目录；默认 ~/.quant_entry_backfill。",
    ).path()

    private val skipExisting by option(
        "--skip-existing",
        help = "跳过 limit_price 已非空的票，只补缺失。",
    ).flag(default = false)

    override fun run() = runBlocking {
        val quantConfig = ConfigManager.load()
        val serverPort = quantConfig.server.port
        val model = AgentModelResolution.resolve(quantConfig.agent, modelKey)

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val effectiveParallelism = if (parallelism <= 0) cpuCores else parallelism
        val targetDate = LocalDate.parse(targetDateOpt)

        // 当日 selected 票（已按模型分降序），取前 topN（默认 5，即全部 selected 票）。
        val selections = DailyProfitPredictionSelectionRepository
            .findSelectionsByTargetDate(targetDate)
            .take(topN)
        if (selections.isEmpty()) {
            echo("[entry-backfill] target_date=$targetDate 无 selected 票，结束")
            return@runBlocking
        }
        // 信号日 = target_date 的上一交易日（as-of 锚）。selection 行自带 tradeDate = 信号日。
        val tasks = selections
            .filter { !skipExisting || it.limitPrice == null }
            .map {
                AgentEntryPriceAnalyzer.StockTask(
                    signalDate = it.tradeDate,
                    executionDate = targetDate,
                    tsCode = it.tsCode,
                )
            }

        val workspaceBase = AgentEntryBackfiller.workspaceFor(targetDate, workspaceBaseDir?.toFile())
        val projectRoot = AgentEntryBackfiller.resolveProjectRoot()

        echo("[entry-backfill] target_date=$targetDate 候选=${selections.size} 待跑=${tasks.size} 并发=$effectiveParallelism" +
            (if (parallelism <= 0) "（自动=CPU $cpuCores 核）" else ""))
        echo("[entry-backfill] 模型=${model.modelId ?: "默认"} provider=${model.provider} server端口=$serverPort")
        if (tasks.isEmpty()) {
            echo("[entry-backfill] 待跑为空（--skip-existing 下买点均已存在），结束")
            return@runBlocking
        }

        val gate = Semaphore(effectiveParallelism)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val results = tasks.map { task ->
            scope.async {
                gate.withPermit {
                    AgentEntryBackfiller.backfillOne(
                        targetDate = targetDate,
                        signalDate = task.signalDate,
                        tsCode = task.tsCode,
                        projectRoot = projectRoot,
                        workspaceBase = workspaceBase,
                        serverPort = serverPort,
                        model = model,
                        perStockTimeoutSec = perStockTimeoutSec,
                        onLog = { echo("[entry-backfill]   $it") },
                    )
                }
            }
        }.awaitAll()

        val filled = results.count { it }
        echo("[entry-backfill] 完成：回填 $filled / ${tasks.size}（缺买点 ${tasks.size - filled} 只回退开盘价）")
    }
}

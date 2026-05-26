package org.shiroumi.backtest.engine

import java.nio.file.Path
import java.util.UUID
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.feed.DbBackedDecisionFeed
import org.shiroumi.backtest.feed.DecisionFileExporter
import org.shiroumi.backtest.feed.ExportResult
import org.shiroumi.backtest.feed.FileBackedDecisionFeed
import org.shiroumi.backtest.feed.InMemoryDecisionFeed
import org.shiroumi.backtest.feed.JsonReplayDecisionFeed
import org.shiroumi.backtest.feed.StrategyDecisionFeed
import org.shiroumi.backtest.output.BacktestSummary
import org.shiroumi.backtest.output.EquityCurveCsvExporter
import org.shiroumi.backtest.output.FileBackedResultWriter
import org.shiroumi.backtest.output.SimulationResult
import org.shiroumi.backtest.output.SimulationResultAggregator
import org.shiroumi.backtest.workspace.BacktestWorkspace

class BacktestRunExecutor(
    private val calendar: TradingCalendar = DbTradingCalendar,
    private val marketDataFeedFactory: (BacktestConfig) -> BacktestMarketDataFeed = { config ->
        DatabaseBacktestMarketDataFeed(config.rules)
    },
    private val aggregator: SimulationResultAggregator = SimulationResultAggregator(),
) {

    fun createRunId(): String = "bt-${UUID.randomUUID()}"

    fun runSimulation(runId: String, config: BacktestConfig, decisionSource: String): SimulationResult {
        val scheduler = BacktestScheduler(
            config = config,
            calendar = calendar,
            marketDataFeed = marketDataFeedFactory(config),
            decisionFeed = decisionFeed(decisionSource),
        )
        return aggregator.aggregate(runId, scheduler.runLoop())
    }

    /**
     * 本地文件模式：把策略决策导出到 [workspace.decisionsDir]，并基于 [FileBackedDecisionFeed] 跑一次回测，
     * 最终把所有产物写入 [workspace.outputDir]，不写任何 DB 表。
     *
     * 对齐 docs/architecture/backtest-engine-design.md §11.4.5：
     *  - `runLocal` 的 `runId` 复用 [BacktestWorkspace.runId]（即目录名 `bt-{timestamp}`）
     *  - `exportDecisions=true`（默认）会按交易日重新导出，覆盖已有 decisions/
     *  - `exportDecisions=false` 仅在调用方明确复用 workspace 时使用（如 `--no-export`）
     *  - 行情仍直读 stock_db，不经 HTTP；DB 连接由 CLI 进程在 main 中初始化
     */
    fun runLocal(
        workspace: BacktestWorkspace,
        config: BacktestConfig,
        exportDecisions: Boolean = true,
    ): LocalBacktestResult {
        val export = if (exportDecisions) {
            exportDecisions(workspace, config)
        } else {
            ExportResult(emptyList())
        }
        val scheduler = BacktestScheduler(
            config = config,
            calendar = calendar,
            marketDataFeed = marketDataFeedFactory(config),
            decisionFeed = FileBackedDecisionFeed(workspace.decisionsDir),
        )
        val result = aggregator.aggregate(workspace.runId, scheduler.runLoop())
        val summary = buildSummary(workspace.runId, config, result)
        val writer = FileBackedResultWriter(workspace.outputDir)
        writer.writeAll(config = config, result = result, summary = summary)
        exportEquityCurveIfNeeded(workspace.runId, config, result)
        return LocalBacktestResult(workspace = workspace, simulation = result, summary = summary, export = export)
    }

    /**
     * 仅导出策略决策到 workspace/decisions/，不跑回测。
     *
     * 用于 `cli backtest export` 子命令。
     */
    fun exportDecisions(workspace: BacktestWorkspace, config: BacktestConfig): ExportResult {
        val executionDates = calendar.tradingDays(config.startDate, config.endDate)
        val exporter = DecisionFileExporter(workspace.decisionsDir)
        return exporter.exportRange(executionDates)
    }

    private fun buildSummary(
        runId: String,
        config: BacktestConfig,
        result: SimulationResult,
    ): BacktestSummary {
        val finalEquity = result.equityCurve.lastOrNull()?.equity?.toDouble()
            ?: config.initialCapital.toDouble()
        return BacktestSummary(
            runId = runId,
            startDate = config.startDate,
            endDate = config.endDate,
            initialCapitalYuan = config.initialCapital.toDouble(),
            finalEquityYuan = finalEquity,
            metrics = result.metrics,
            orderCount = result.orders.size,
            auditCount = result.audits.size,
            tradingDays = result.equityCurve.size,
        )
    }

    private fun decisionFeed(source: String): StrategyDecisionFeed {
        return when {
            source.equals("audit", ignoreCase = true) -> DbBackedDecisionFeed()
            source.equals("inmem", ignoreCase = true) -> InMemoryDecisionFeed(emptyList())
            source.startsWith("json:", ignoreCase = true) -> JsonReplayDecisionFeed(Path.of(source.substringAfter(":")))
            else -> error("Unsupported decisions source: $source")
        }
    }

    fun exportEquityCurveIfNeeded(runId: String, config: BacktestConfig, result: SimulationResult) {
        val template = config.output.equityCurveCsv ?: return
        val path = Path.of(template.replace("{run_id}", runId))
        EquityCurveCsvExporter.export(result.equityCurve, path)
    }
}

/**
 * 本地文件模式回测的完整产物，便于 CLI 一次性消费。
 */
data class LocalBacktestResult(
    val workspace: BacktestWorkspace,
    val simulation: SimulationResult,
    val summary: BacktestSummary,
    val export: ExportResult,
)

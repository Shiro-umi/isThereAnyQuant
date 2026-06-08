package org.shiroumi.backtest.engine

import java.nio.file.Path
import java.util.UUID
import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.feed.DbBackedDecisionFeed
import org.shiroumi.backtest.feed.DecisionFileExporter
import org.shiroumi.backtest.feed.ExportResult
import org.shiroumi.backtest.feed.FileBackedDecisionFeed
import org.shiroumi.backtest.feed.InMemoryDecisionFeed
import org.shiroumi.backtest.feed.JsonReplayDecisionFeed
import org.shiroumi.backtest.feed.PreloadedDecisionFeed
import org.shiroumi.backtest.feed.StrategyDecisionFeed
import org.shiroumi.backtest.feed.isLimitUpOnTradeDate
import org.shiroumi.backtest.feed.preloadLimitUpSymbols
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
     * 本地文件模式：把策略决策导出到 [workspace.decisionsDir]，并基于预加载的全内存数据跑一次回测，
     * 最终把所有产物写入 [workspace.outputDir]，不写任何 DB 表。
     *
     * 预加载优化：
     * - 行情数据：构造时 1 次批量查询 `stock_daily_data`，替代逐日 500-750 次 DB 查询
     * - 决策数据：构造时 1 次批量查询 `daily_profit_prediction_selection`，替代逐日 250 次 DB 查询
     * - 回测主循环全程零 DB 访问
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
        filterLimitUp: Boolean = false,
        includeAuditSignalsWhenTargetsMissing: Boolean = true,
        /** 持仓退出规则。为 null 时不启用自动退出管理（保持原有每日调仓行为）。 */
        exitRules: ExitRulesConfig? = null,
    ): LocalBacktestResult {
        val dates = calendar.tradingDays(config.startDate, config.endDate)

        // 预加载行情数据：1 次批量查询替代逐日 DB 访问
        val marketFeed = PreloadedMarketDataFeed.fromDatabase(
            rules = config.rules,
            from = config.startDate,
            to = config.endDate,
        )

        // 预加载决策数据：1-2 次批量查询替代逐日 DB 访问
        val decisionFeed: StrategyDecisionFeed
        val export: ExportResult

        // 若需涨停过滤，用预加载的蜡烛和日历数据在内存中批量计算涨停集合
        val limitUpSymbols = if (filterLimitUp) {
            val symbolMap: Map<LocalDate, Set<String>> = preloadLimitUpSymbols(
                dates = dates,
                candlesByDate = marketFeed.candlesByDate,
                openDates = marketFeed.openDates,
            )
            fun lookup(date: LocalDate): Set<String> = symbolMap[date].orEmpty()
            ::lookup
        } else {
            fun noFilter(date: LocalDate): Set<String> = emptySet()
            ::noFilter
        }

        if (exportDecisions) {
            val preloadedFeed = PreloadedDecisionFeed.fromDatabase(
                executionDates = dates,
                filterSignalLimitUp = filterLimitUp,
                limitUpSymbols = limitUpSymbols,
                includeAuditFallback = includeAuditSignalsWhenTargetsMissing,
            )
            decisionFeed = preloadedFeed
            // 用预加载的 feed 导出到文件：纯内存查询 + 文件写入，零 DB
            export = exportFromPreloaded(workspace, dates, preloadedFeed)
        } else {
            decisionFeed = FileBackedDecisionFeed(workspace.decisionsDir)
            export = ExportResult(emptyList())
        }

        val exitManager = exitRules?.let {
            PositionExitManager(
                config = it,
                calendar = calendar,
                marketDataFeed = marketFeed,
            )
        }
        val scheduler = BacktestScheduler(
            config = config,
            calendar = calendar,
            marketDataFeed = marketFeed,
            decisionFeed = decisionFeed,
            exitManager = exitManager,
        )
        val result = aggregator.aggregate(workspace.runId, scheduler.runLoop())
        val summary = buildSummary(workspace.runId, config, result)
        val writer = FileBackedResultWriter(workspace.outputDir)
        writer.writeAll(config = config, result = result, summary = summary)
        exportEquityCurveIfNeeded(workspace.runId, config, result)
        return LocalBacktestResult(workspace = workspace, simulation = result, summary = summary, export = export)
    }

    private fun exportFromPreloaded(
        workspace: BacktestWorkspace,
        dates: List<LocalDate>,
        feed: StrategyDecisionFeed,
    ): ExportResult {
        val exporter = DecisionFileExporter(workspace.decisionsDir, feed)
        return exporter.exportRange(dates)
    }

    /**
     * 仅导出策略决策到 workspace/decisions/，不跑回测。
     *
     * 用于 `cli backtest export` 子命令。
     */
    fun exportDecisions(
        workspace: BacktestWorkspace,
        config: BacktestConfig,
        filterLimitUp: Boolean = false,
        includeAuditSignalsWhenTargetsMissing: Boolean = true,
    ): ExportResult {
        val executionDates = calendar.tradingDays(config.startDate, config.endDate)
        // 使用预加载决策源：批量查询 → 内存转换 → 逐文件写出
        val preloadedFeed = PreloadedDecisionFeed.fromDatabase(
            executionDates = executionDates,
            filterSignalLimitUp = filterLimitUp,
            includeAuditFallback = includeAuditSignalsWhenTargetsMissing,
        )
        return exportFromPreloaded(workspace, executionDates, preloadedFeed)
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

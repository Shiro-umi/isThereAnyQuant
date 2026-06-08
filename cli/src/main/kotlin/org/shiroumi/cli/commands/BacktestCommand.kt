package org.shiroumi.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.datetime.LocalDate
import kotlin.math.pow
import kotlin.math.round
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.config.MatchingPolicyConfig
import org.shiroumi.backtest.config.MatchingPolicyKind
import org.shiroumi.backtest.config.OutputConfig
import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.engine.BacktestRunExecutor
import org.shiroumi.backtest.engine.ExitRulesConfig
import org.shiroumi.backtest.engine.LocalBacktestResult
import org.shiroumi.backtest.feed.ExportResult
import org.shiroumi.backtest.output.PerformanceMetrics
import org.shiroumi.backtest.workspace.BacktestWorkspace
import org.shiroumi.config.ConfigManager

/**
 * `./cli backtest` 入口。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11：
 *  - `backtest run`     —— 创建/复用工作区，导出决策、跑回测、写产物
 *  - `backtest export`  —— 只导出决策到指定目录（默认 `.backtest/{bt-...}/decisions/`），不跑回测
 *
 * CLI 通过 `config.yaml` 直连 stock_db；不经过 Ktor。
 */
class BacktestCommand : CliktCommand(
    name = "backtest",
    help = "本地隔离模式回测：从 stock_db 导出策略决策、跑回测、把结果写到 .backtest/ 工作区。"
) {
    init {
        subcommands(BacktestRunCommand(), BacktestExportCommand(), BacktestQueryCommand())
    }

    override fun run() = Unit
}

/**
 * `./cli backtest run` —— 一键回测。
 *
 * 默认创建新的 `bt-{timestamp}` 工作区并重新导出 decisions；如显式 `--workspace`
 * 也会重新导出覆盖已有 decisions，除非追加 `--no-export`。
 */
class BacktestRunCommand : CliktCommand(
    name = "run",
    help = "执行一次本地隔离模式回测，输出到 .backtest/{bt-...}/output/"
) {
    private val start by option("--start", help = "起始执行日（YYYY-MM-DD）").default("2024-01-02")
    private val end by option("--end", help = "结束执行日（YYYY-MM-DD）").default("2024-12-31")
    private val capital by option("--capital", help = "初始资金，单位元").double().default(1_000_000.0)
    private val workspaceDir by option(
        "--workspace",
        help = "复用已有工作区目录（不指定则创建新的 .backtest/bt-{timestamp}/）",
    ).path()
    private val baseDir by option(
        "--base-dir",
        help = "新建工作区时使用的基目录，默认 .backtest/",
    ).path().default(Paths.get(".backtest"))
    private val noExport by option(
        "--no-export",
        help = "跳过决策导出，直接使用 workspace/decisions/ 中已有文件",
    ).flag(default = false)
    private val matching by option(
        "--matching",
        help = "撮合策略：OPEN_PRICE / VWAP / CLOSE_PRICE / LIMIT",
    ).choice(
        MatchingPolicyKind.OPEN_PRICE.name to MatchingPolicyKind.OPEN_PRICE,
        MatchingPolicyKind.VWAP.name to MatchingPolicyKind.VWAP,
        MatchingPolicyKind.CLOSE_PRICE.name to MatchingPolicyKind.CLOSE_PRICE,
        MatchingPolicyKind.LIMIT.name to MatchingPolicyKind.LIMIT,
    ).default(MatchingPolicyKind.OPEN_PRICE)
    private val slippageBps by option(
        "--slippage-bps",
        help = "滑点（基点制，1bp=0.01%）；BUY 向上、SELL 向下",
    ).int().default(SlippageConfig().basisPoints)
    private val equityCurveCsv by option(
        "--equity-curve-csv",
        help = "额外导出 equity 曲线 CSV，路径支持 {run_id} 占位",
    )
    private val abandonSignalLimitUp by option(
        "--abandon-signal-limit-up",
        help = "如果信号产生日（选出日）已经是涨停价，是否在执行日放弃买入",
    ).flag(default = false)
    private val filterLimitUp by option(
        "--filter-limit-up",
        help = "导出决策时过滤掉选出日涨停的股票，并按模型分从全部未涨停候选中取 Top5",
    ).flag(default = false)
    private val targetOnly by option(
        "--target-only",
        help = "只使用 daily_profit_prediction_selection 目标组合，不在缺失目标组合时降级读取 daily_strategy_audit",
    ).flag(default = false)
    private val takeProfit by option(
        "--take-profit",
        help = "止盈比例（从入场价算起），如 0.07 = 7%；不指定则不启用持仓退出管理",
    ).double()
    private val timeStop by option(
        "--time-stop",
        help = "时间止损天数（从入场日起算），默认 3（T+3）",
    ).int().default(3)
    private val priceStop by option(
        "--price-stop",
        help = "启用价格止损：T+2/T+3 收盘 < T 日最低点时清仓",
    ).flag(default = false)
    private val t1NoSell by option(
        "--t1-no-sell",
        help = "禁止在入场当日（T+1）卖出",
    ).flag(default = true)

    override fun run() {
        ConfigManager.load()
        val workspace = workspaceDir?.let { BacktestWorkspace.open(it) }
            ?: BacktestWorkspace.createForRun(baseDir = baseDir)
        val config = buildConfig(equityCurveCsv = equityCurveCsv)
        echo("[1/4] 工作区 → ${workspace.rootDir}")
        if (noExport) {
            echo("[2/4] 跳过决策导出（--no-export）")
        } else {
            echo("[2/4] 导出策略决策 → ${workspace.decisionsDir}")
        }
        echo("[3/4] 运行回测模拟…")
        val executor = BacktestRunExecutor()
        val exitRules = takeProfit?.let { tp ->
            ExitRulesConfig(
                takeProfitPct = tp,
                timeStopDays = timeStop,
                priceStopEnabled = priceStop,
                t1NoSell = t1NoSell,
            )
        }
        if (exitRules != null) {
            echo("       持仓退出规则：止盈 +${(exitRules.takeProfitPct * 100).toInt()}% / " +
                "T+${exitRules.timeStopDays} 时间止损 / " +
                "价格止损${if (exitRules.priceStopEnabled) "开启" else "关闭"} / " +
                "T+1 禁售${if (exitRules.t1NoSell) "开启" else "关闭"}")
        }
        val result = executor.runLocal(
            workspace = workspace,
            config = config,
            exportDecisions = !noExport,
            filterLimitUp = filterLimitUp,
            includeAuditSignalsWhenTargetsMissing = !targetOnly,
            exitRules = exitRules,
        )
        if (!noExport) {
            echo("       决策导出：${result.export.writtenDays}/${result.export.totalDays} 个交易日，" +
                "共 ${result.export.totalDecisions} 条决策")
        }
        echo("[4/4] 绩效摘要")
        printSummary(result)
        echo("       详情: ${workspace.outputDir}")
    }

    private fun buildConfig(equityCurveCsv: String?): BacktestConfig {
        val startDate = LocalDate.parse(start)
        val endDate = LocalDate.parse(end)
        return BacktestConfig(
            startDate = startDate,
            endDate = endDate,
            initialCapital = Money.ofYuan(capital),
            matching = MatchingPolicyConfig(policy = matching),
            slippage = SlippageConfig(basisPoints = slippageBps),
            rules = org.shiroumi.backtest.config.RulesConfig(
                abandonIfSignalLimitUp = abandonSignalLimitUp
            ),
            output = OutputConfig(equityCurveCsv = equityCurveCsv),
        )
    }

    private fun printSummary(result: LocalBacktestResult) {
        val metrics = result.simulation.metrics
        echo("       总收益率:      ${formatPercent(metrics.totalReturn)}")
        echo("       年化收益:      ${formatPercent(metrics.annualizedReturn)}")
        echo("       最大回撤:      -${formatPercent(metrics.maxDrawdown)}")
        echo("       夏普比率:      ${formatRatio(metrics.sharpe)}")
        echo("       索提诺比率:    ${formatRatio(metrics.sortino)}")
        echo("       胜率:          ${formatPercent(metrics.winRate)}")
        echo("       换手率:        ${formatRatio(metrics.turnover)}")
        echo("       平均持仓天数:  ${formatRatio(metrics.avgHoldingDays)} 天")
        echo("       期末权益:      ${formatYuan(result.summary.finalEquityYuan)}（初始 ${formatYuan(result.summary.initialCapitalYuan)}）")
    }
}

/**
 * `./cli backtest export` —— 仅导出决策。
 *
 * 当需要单独检查 daily_profit_prediction_selection / daily_strategy_audit 落地结构，
 * 或者把 decisions 文件交给其他工具消费时使用。
 */
class BacktestExportCommand : CliktCommand(
    name = "export",
    help = "仅把策略决策导出为 decisions/{date}.json，不跑回测。"
) {
    private val start by option("--start", help = "起始执行日（YYYY-MM-DD）").default("2024-01-02")
    private val end by option("--end", help = "结束执行日（YYYY-MM-DD）").default("2024-12-31")
    private val to by option(
        "--to",
        help = "导出目标目录（不存在会自动创建）。也可作为 BacktestWorkspace 复用入口。",
    ).path()
    private val baseDir by option(
        "--base-dir",
        help = "未指定 --to 时使用的基目录，默认 .backtest/",
    ).path().default(Paths.get(".backtest"))
    private val targetOnly by option(
        "--target-only",
        help = "只导出 daily_profit_prediction_selection 目标组合，不在缺失目标组合时降级读取 daily_strategy_audit",
    ).flag(default = false)

    override fun run() {
        ConfigManager.load()
        val workspace = to?.let { BacktestWorkspace.open(it) }
            ?: BacktestWorkspace.createForRun(baseDir = baseDir)
        echo("[1/2] 工作区 → ${workspace.rootDir}")
        val config = BacktestConfig(
            startDate = LocalDate.parse(start),
            endDate = LocalDate.parse(end),
            initialCapital = Money.ofYuan(1.0),
        )
        echo("[2/2] 导出策略决策 → ${workspace.decisionsDir}")
        val export = BacktestRunExecutor().exportDecisions(
            workspace = workspace,
            config = config,
            includeAuditSignalsWhenTargetsMissing = !targetOnly,
        )
        echo("       导出完成：${export.writtenDays}/${export.totalDays} 个交易日，" +
            "共 ${export.totalDecisions} 条决策（空白日 ${export.emptyDays} 个）")
    }
}

private fun formatPercent(value: Double): String {
    val rounded = round(value * 10000.0) / 100.0
    return "${rounded}%"
}

private fun formatRatio(value: Double): String {
    val rounded = round(value * 100.0) / 100.0
    return rounded.toString()
}

private fun formatYuan(value: Double): String {
    val rounded = round(value * 100.0) / 100.0
    return "${rounded} 元"
}

class BacktestQueryCommand : CliktCommand(
    name = "query",
    help = "查询 daily_profit_prediction_selection 数据概况以确定可用回测日期范围"
) {
    override fun run() {
        ConfigManager.load()
        val summary = org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository.findDataSummary()
        echo(summary)
    }
}

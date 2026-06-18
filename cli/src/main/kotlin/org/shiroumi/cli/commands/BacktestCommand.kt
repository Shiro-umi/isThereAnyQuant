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
import org.shiroumi.backtest.engine.EntryOrdering
import org.shiroumi.backtest.feed.AgentEntryPriceFeed
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
    private val operatingPoint by option(
        "--operating-point",
        help = "一键套用生产运营点（含全部默认字段）：tp8-h3（tp8/u25/H3 含浅止损 -3%）/ " +
            "tp8-h3-noshallow（同上但关闭浅止损）；指定后即启用持仓退出管理，逐参数选项可二次覆盖",
    ).choice(
        "tp8-h3" to ExitRulesConfig.TP8_H3,
        "tp8-h3-noshallow" to ExitRulesConfig.TP8_H3_NO_SHALLOW,
    )
    private val takeProfit by option(
        "--take-profit",
        help = "止盈比例（从入场价算起），如 0.08 = 8%；不指定 --operating-point 时，给定此项即启用持仓退出管理",
    ).double()
    private val timeStop by option(
        "--time-stop",
        help = "时间止损天数（从入场日起算），默认 3（H3）",
    ).int()
    private val priceStop by option(
        "--price-stop",
        help = "启用价格止损：收盘 < 信号日最低点时清仓（生产默认关闭）",
    ).flag()
    private val shallowStop by option(
        "--shallow-stop",
        help = "浅浮亏止损线（负数开启，如 -0.03 = 收盘跌破入场价 ×0.97 离场）；传 0 关闭",
    ).double()
    private val entryGap by option(
        "--entry-gap",
        help = "入场跳空上限（开盘较信号日收盘涨幅超过即放弃入场），如 0.03 = 3%；非正数关闭",
    ).double()
    private val maxDailyEntries by option(
        "--max-daily-entries",
        help = "每日新入场上限，默认 1（仅入场 entryPriority 最高的一只）；0 表示不限",
    ).int()
    private val profitLadder by option(
        "--profit-ladder",
        help = "保盈阶梯，格式 'day:level,day:level'，如 '1:0.025,2:0.025'；空串关闭",
    )
    private val t1NoSell by option(
        "--t1-no-sell",
        help = "禁止在入场当日（T+1）卖出",
    ).flag(default = true)
    private val positionSizing by option(
        "--position-sizing",
        help = "仓位口径（仅启用入场闸门时生效）：full = 单票满仓滚动（每笔用全部可用资金，贴近每日 1 只字面执行）；" +
            "equal = 沿用选股原始权重（等权切片，约 0.2/只，大量现金闲置）。默认 full。",
    ).choice("full" to true, "equal" to false).default(true)
    private val entryOrder by option(
        "--entry-order",
        help = "入场排序口径：volatility = 信号日 20 日波动率降序（复刻生产）；model-score = 按模型分降序选前 N。默认 volatility。",
    ).choice("volatility" to EntryOrdering.VOLATILITY, "model-score" to EntryOrdering.MODEL_SCORE)
        .default(EntryOrdering.VOLATILITY)
    private val equalWeightEntries by option(
        "--equal-weight-entries",
        help = "入场仓位按当日实际入场只数等权（各 1/N，如每日 3 只各 1/3）；仅 --position-sizing equal 时生效。",
    ).flag(default = false)
    private val agentEntryPricesDir by option(
        "--agent-entry-prices",
        help = "Agent 买点价目录（独立于引擎 decisions/）：读取 {dir}/{执行日}.json 中 BUY/LIMIT 决策的 limitPrice，" +
            "入场闸门据此按限价买点（T+1 开盘按限价撮合）入场；不指定则按开盘价入场。仅启用入场闸门时生效。",
    ).path()

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
        val exitRules = buildExitRules()
        if (exitRules != null) {
            val shallowDesc = if (exitRules.shallowStopLossPct < 0.0)
                "浅止损 ${(exitRules.shallowStopLossPct * 100).toInt()}%" else "浅止损关闭"
            val ladderDesc = if (exitRules.profitProtectLadder.isEmpty()) "无阶梯"
            else "保盈阶梯 " + exitRules.profitProtectLadder.entries.joinToString(",") { "${it.key}日:${(it.value * 100)}%" }
            echo("       持仓退出规则：止盈 +${(exitRules.takeProfitPct * 100).toInt()}% / " +
                "H${exitRules.timeStopDays} 时间止损 / " +
                "$ladderDesc / $shallowDesc / " +
                "价格止损${if (exitRules.priceStopEnabled) "开启" else "关闭"} / " +
                "每日入场 ${exitRules.maxDailyEntries} / " +
                "入场跳空上限 ${if (exitRules.entryGapMaxPct > 0.0) "${(exitRules.entryGapMaxPct * 100)}%" else "关闭"} / " +
                "T+1 禁售${if (exitRules.t1NoSell) "开启" else "关闭"} / " +
                "仓位口径 ${if (positionSizing) "单票满仓" else "等权切片"}")
        }
        val agentEntryPriceFeed = agentEntryPricesDir?.let { AgentEntryPriceFeed(it) }
        if (agentEntryPriceFeed != null) {
            echo("       Agent 买点价目录：$agentEntryPricesDir（按 BUY/LIMIT limitPrice 限价入场）")
        }
        val result = executor.runLocal(
            workspace = workspace,
            config = config,
            exportDecisions = !noExport,
            filterLimitUp = filterLimitUp,
            includeAuditSignalsWhenTargetsMissing = !targetOnly,
            exitRules = exitRules,
            fullPositionPerEntry = positionSizing,
            entryOrdering = entryOrder,
            equalWeightAcrossEntries = equalWeightEntries,
            agentEntryPriceFeed = agentEntryPriceFeed,
        )
        if (!noExport) {
            echo("       决策导出：${result.export.writtenDays}/${result.export.totalDays} 个交易日，" +
                "共 ${result.export.totalDecisions} 条决策")
        }
        echo("[4/4] 绩效摘要")
        printSummary(result)
        echo("       详情: ${workspace.outputDir}")
    }

    /**
     * 装配持仓退出规则。
     * - 给定 --operating-point 时以该预设为基；否则给定 --take-profit 时以 tp8-h3 默认为基。
     * - 两者都未给定 → 返回 null（不启用持仓退出管理，保持原有每日调仓行为）。
     * - 任意逐参数选项二次覆盖对应字段。
     */
    private fun buildExitRules(): ExitRulesConfig? {
        val base = operatingPoint ?: takeProfit?.let { ExitRulesConfig() } ?: return null
        val ladder = profitLadder?.let { spec ->
            spec.split(',')
                .filter { it.isNotBlank() }
                .associate { entry ->
                    val (day, level) = entry.split(':', limit = 2)
                    day.trim().toInt() to level.trim().toDouble()
                }
        } ?: base.profitProtectLadder
        return base.copy(
            takeProfitPct = takeProfit ?: base.takeProfitPct,
            timeStopDays = timeStop ?: base.timeStopDays,
            priceStopEnabled = if (priceStop) true else base.priceStopEnabled,
            t1NoSell = t1NoSell,
            shallowStopLossPct = shallowStop ?: base.shallowStopLossPct,
            entryGapMaxPct = entryGap ?: base.entryGapMaxPct,
            maxDailyEntries = maxDailyEntries ?: base.maxDailyEntries,
            profitProtectLadder = ladder,
        )
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

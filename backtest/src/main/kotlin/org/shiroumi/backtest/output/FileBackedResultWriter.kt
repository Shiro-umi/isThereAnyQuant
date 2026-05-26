package org.shiroumi.backtest.output

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow
import kotlin.math.round
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.config.BacktestConfig

/**
 * 本地文件模式回测结果写入器。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.4.4：
 *  - 输出目录由调用方传入（通常是 `BacktestWorkspace.outputDir`）
 *  - 所有 JSON 序列化复用同一份 [Json] 实例，保证字段顺序与缩进一致
 *  - `equity_curve.jsonl` 中的 `drawdown` 字段由 Writer 自行计算
 *  - `equity_curve.csv` 仍走 [EquityCurveCsvExporter]，路径由调用方决定
 */
class FileBackedResultWriter(
    private val outputDir: Path,
    private val json: Json = ResultWriterJson,
) {
    init {
        Files.createDirectories(outputDir)
    }

    /** 配置快照：方便后续复盘时还原本次回测使用的参数。 */
    fun writeConfig(config: BacktestConfig) {
        write(BACKTEST_CONFIG_FILE, json.encodeToString(config))
    }

    fun writeOrders(orders: List<OrderRecord>) {
        writeJsonl(ORDERS_FILE, orders) { json.encodeToString(it) }
    }

    fun writeEquityCurve(curve: List<EquityPoint>) {
        var peak = 0.0
        val lines = curve.map { point ->
            val equity = point.equity.toDouble()
            if (equity > peak) peak = equity
            val drawdown = if (peak > 0.0) (peak - equity) / peak else 0.0
            json.encodeToString(
                EquityCurveLine(
                    tradeDate = point.tradeDate,
                    cash = round4(point.cash.toDouble()),
                    positionValue = round4(point.positionValue.toDouble()),
                    equity = round4(equity),
                    drawdown = round4(drawdown),
                )
            )
        }
        writeLines(EQUITY_CURVE_FILE, lines)
    }

    fun writeAudits(audits: List<TradeAudit>) {
        writeJsonl(AUDITS_FILE, audits) { json.encodeToString(it) }
    }

    /** 逐日持仓快照：每只标的的 qty / availableQty / lockedTodayQty / avgCost。 */
    fun writePositions(positions: List<DailyPositionSnapshot>) {
        writeJsonl(POSITIONS_FILE, positions) { json.encodeToString(it) }
    }

    /** 现金流序列：买卖回款、佣金、过户费、印花税、分红等。 */
    fun writeCashFlows(cashFlows: List<CashFlow>) {
        writeJsonl(CASH_FLOWS_FILE, cashFlows) { json.encodeToString(it) }
    }

    /** 每段建仓 → 清仓生命周期的贡献明细，用于单票贡献排序。 */
    fun writeLotContributions(lots: List<LotContribution>) {
        writeJsonl(LOT_CONTRIBUTIONS_FILE, lots) { json.encodeToString(it) }
    }

    fun writeMetrics(metrics: PerformanceMetrics) {
        write(METRICS_FILE, json.encodeToString(metrics))
    }

    fun writeSummary(summary: BacktestSummary) {
        write(SUMMARY_FILE, json.encodeToString(summary))
    }

    /** 完整写入（按推荐顺序），用于 [org.shiroumi.backtest.engine.BacktestRunExecutor.runLocal]。 */
    fun writeAll(
        config: BacktestConfig,
        result: SimulationResult,
        summary: BacktestSummary,
    ) {
        writeConfig(config)
        writeOrders(result.orders)
        writeEquityCurve(result.equityCurve)
        writePositions(result.positions)
        writeCashFlows(result.cashFlows)
        writeAudits(result.audits)
        writeLotContributions(result.lotContributions)
        writeMetrics(result.metrics)
        writeSummary(summary)
    }

    private fun write(name: String, content: String) {
        Files.writeString(outputDir.resolve(name), content)
    }

    private fun writeLines(name: String, lines: List<String>) {
        Files.writeString(outputDir.resolve(name), lines.joinToString("\n"))
    }

    private fun <T> writeJsonl(name: String, items: List<T>, encode: (T) -> String) {
        writeLines(name, items.map(encode))
    }

    private fun round4(value: Double): Double {
        val scale = 10.0.pow(4)
        return round(value * scale) / scale
    }

    companion object {
        const val BACKTEST_CONFIG_FILE = "backtest_config.json"
        const val ORDERS_FILE = "orders.jsonl"
        const val EQUITY_CURVE_FILE = "equity_curve.jsonl"
        const val POSITIONS_FILE = "positions.jsonl"
        const val CASH_FLOWS_FILE = "cash_flows.jsonl"
        const val AUDITS_FILE = "audits.jsonl"
        const val LOT_CONTRIBUTIONS_FILE = "lot_contributions.jsonl"
        const val METRICS_FILE = "metrics.json"
        const val SUMMARY_FILE = "summary.json"
    }
}

/**
 * 本地模式 CLI 标准输出对应的结构化汇总。
 *
 * 字段与 `[4/4] 打印绩效摘要` 一节的展示一致，便于脚本消费。
 */
@Serializable
data class BacktestSummary(
    val runId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapitalYuan: Double,
    val finalEquityYuan: Double,
    val metrics: PerformanceMetrics,
    val orderCount: Int,
    val auditCount: Int,
    val tradingDays: Int,
)

@Serializable
private data class EquityCurveLine(
    val tradeDate: LocalDate,
    val cash: Double,
    val positionValue: Double,
    val equity: Double,
    val drawdown: Double,
)

internal val ResultWriterJson: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 把 `daily_profit_prediction_selection` / `daily_strategy_audit` 等策略中间产物
 * 导出为 `decisions/{executionDate}.json`。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.4.2：
 *  - **唯一耦合点**：策略 schema 变化只会影响此处的 DB → 决策映射逻辑
 *  - 默认通过 [DbBackedDecisionFeed] 复用 §M5 的现成适配器，避免在两处维护
 *    相同的"账户字段剥离"逻辑
 *  - 输出文件 schema 由 [DecisionFile] 与 [DecisionFileJson] 共同保证稳定
 *  - 缺失策略数据的执行日不会写文件，等同于 `FileBackedDecisionFeed` 读到空决策
 */
class DecisionFileExporter(
    private val decisionsDir: Path,
    private val feed: StrategyDecisionFeed = DbBackedDecisionFeed(),
    private val json: Json = DecisionFileJson,
) {
    companion object {
        fun createWithLimitUpFilter(decisionsDir: Path): DecisionFileExporter {
            return DecisionFileExporter(
                decisionsDir = decisionsDir,
                feed = DbBackedDecisionFeed(
                    filterSignalLimitUp = true,
                    limitUpChecker = ::isLimitUpOnTradeDate,
                ),
            )
        }
    }
    init {
        Files.createDirectories(decisionsDir)
    }

    /**
     * 导出指定执行日列表对应的决策文件。
     *
     * **执行前会清空 [decisionsDir] 下既有的 `*.json` 文件**，确保「复用 workspace
     * 重新导出」时不残留上一次的过期决策——这是文档 §11.2 中
     * 「`run` 每次默认创建新目录，天然满足『每次重新回测清空临时文件夹』」
     * 在「复用 workspace + 重新导出」场景下的等价兜底。
     *
     * @return 每个执行日的导出结果，包括「无决策跳过」与「写入条数」两类。
     */
    fun exportRange(executionDates: List<LocalDate>): ExportResult {
        clearExistingDecisionFiles()
        val entries = executionDates.map { date ->
            val decisions = feed.decisionsFor(date)
            if (decisions.isEmpty()) {
                ExportEntry(date, written = false, decisionCount = 0)
            } else {
                writeDecisionFile(date, decisions)
                ExportEntry(date, written = true, decisionCount = decisions.size)
            }
        }
        return ExportResult(entries)
    }

    private fun clearExistingDecisionFiles() {
        if (!Files.isDirectory(decisionsDir)) return
        Files.newDirectoryStream(decisionsDir, "*.json").use { stream ->
            for (path in stream) Files.deleteIfExists(path)
        }
    }

    private fun writeDecisionFile(executionDate: LocalDate, decisions: List<StrategyDecision>) {
        val payload = DecisionFile(executionDate = executionDate, decisions = decisions)
        val text = json.encodeToString(payload)
        Files.writeString(decisionsDir.resolve("$executionDate.json"), text)
    }
}

/** 单个执行日的导出结果。 */
data class ExportEntry(
    val executionDate: LocalDate,
    /** 是否真的写出了文件（DB 当日无决策时为 false）。 */
    val written: Boolean,
    val decisionCount: Int,
)

/** 一次 `exportRange` 调用的汇总。 */
data class ExportResult(val entries: List<ExportEntry>) {
    val totalDays: Int get() = entries.size
    val writtenDays: Int get() = entries.count { it.written }
    val emptyDays: Int get() = entries.count { !it.written }
    val totalDecisions: Int get() = entries.sumOf { it.decisionCount }
}

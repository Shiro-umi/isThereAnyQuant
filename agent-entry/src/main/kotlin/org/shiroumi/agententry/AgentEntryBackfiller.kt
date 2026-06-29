package org.shiroumi.agententry

import java.io.File
import kotlinx.datetime.LocalDate
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository

/**
 * 单只 agent 买点回填的结果。
 *
 * [ok] 为 true 时 [limitPrice] 非空表示成功回填的有效买点；[ok] 为 false 时 [limitPrice] 一律为 null。
 */
data class BackfillResult(
    val ok: Boolean,
    val limitPrice: Double?,
)

/** 根据 tsCode 前缀判定该股的日涨跌停幅度（小数，如 0.10 = 10%）。 */
fun dailyPriceLimitPct(tsCode: String): Double {
    val code = tsCode.substringBefore(".")
    val market = tsCode.substringAfter(".", "")
    return when {
        market.equals("BJ", ignoreCase = true) -> 0.20
        code.startsWith("688") -> 0.20
        code.startsWith("300") -> 0.20
        code.startsWith("301") -> 0.20
        else -> 0.10
    }
}

/**
 * agent 买点回填的共享脚手架——CLI（`agent-entry-backfill` 手动入口）与生产盘后
 * （`AgentEntryBackfillStep`）共用单只「分析 → 解析 → 写库」三步、projectRoot 解析与 workspace 路径派生。
 *
 * 边界：本对象只承载与回填编排无关的两端公共点（路径/单只回填），不持有并发策略——
 * 并发度、跳过已存在、日志载体（echo/logger）、结果统计（CLI fire-and-forget / 盘后 Outcome 覆盖率）
 * 是两端各自语义，留在各自调用层。
 */
object AgentEntryBackfiller {

    /** 回填 workspace 基目录名（`~/.quant_entry_backfill`）。 */
    private const val WORKSPACE_DIR_NAME = ".quant_entry_backfill"

    /** runId 前缀，workspace 子目录 = `entry-backfill-{targetDate}`。 */
    private const val RUN_ID_PREFIX = "entry-backfill-"

    /**
     * 解析项目根目录：`quant.project.root` → `quant.projectRoot` → `user.dir`。
     *
     * 统一回退链，消除 CLI/Service 各写一份导致的漂移（CLI 曾缺 `quant.projectRoot` 中间档）。
     */
    fun resolveProjectRoot(): File = File(
        System.getProperty("quant.project.root")
            ?: System.getProperty("quant.projectRoot")
            ?: System.getProperty("user.dir")
    )

    /**
     * 某执行日的回填 workspace（`{base}/entry-backfill-{targetDate}`）。
     *
     * @param override 显式覆盖基目录（CLI `--workspace-base`）；为空时用 `~/.quant_entry_backfill`。
     */
    fun workspaceFor(targetDate: LocalDate, override: File? = null): File {
        val base = override ?: File(System.getProperty("user.home"), WORKSPACE_DIR_NAME)
        return File(base, "$RUN_ID_PREFIX$targetDate")
    }

    /**
     * 单只票回填：跑 agent 分析 → 解析买点 → 成功且有买点则回填 `limit_price` 返回 true。
     *
     * 失败/无买点不写库（持仓推进回退开盘价无条件建仓），返回 false。本函数不吞 [analyzeOneStock]
     * 之外的异常——并发兜底（runCatching）由各端编排层按自身失败语义决定。
     */
    suspend fun backfillOne(
        targetDate: LocalDate,
        signalDate: LocalDate,
        tsCode: String,
        projectRoot: File,
        workspaceBase: File,
        serverPort: Int,
        model: org.shiroumi.config.AgentModelResolution.Resolved,
        perStockTimeoutSec: Long,
        onLog: (String) -> Unit = {},
    ): BackfillResult {
        val result = AgentEntryPriceAnalyzer.analyzeOneStock(
            projectRoot = projectRoot,
            workspaceBase = workspaceBase,
            serverPort = serverPort,
            model = model,
            task = AgentEntryPriceAnalyzer.StockTask(
                signalDate = signalDate,
                executionDate = targetDate,
                tsCode = tsCode,
            ),
            perStockTimeoutSec = perStockTimeoutSec,
            onLog = onLog,
        )
        val limit = result.perStockFile?.let(AgentEntryPriceAnalyzer::parseBuyPoint)
        return if (result.ok && limit != null) {
            val rows = DailyProfitPredictionSelectionRepository.updateLimitPrice(targetDate, tsCode, limit)
            onLog("✔ $tsCode 买点=$limit 回填行数=$rows")
            BackfillResult(ok = rows > 0, limitPrice = if (rows > 0) limit else null)
        } else {
            onLog("✘ $tsCode 无买点（失败或未产出），回退开盘价建仓")
            BackfillResult(ok = false, limitPrice = null)
        }
    }
}

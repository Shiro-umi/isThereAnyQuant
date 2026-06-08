package org.shiroumi.strategy.research.tuner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 单次试探记录。
 *
 * @property iter         迭代序号（从 0 开始）。
 * @property params       本次试探使用的参数（已序列化为 `Map<String, String>`，与
 *                        [ResearchContext.params] 同构）。
 * @property observation  本次试探的观测结果。
 * @property tookMillis   本次试探耗时（毫秒）。
 */
@Serializable
data class TrialRecord(
    val iter: Int,
    val params: Map<String, String>,
    val observation: Observation,
    val tookMillis: Long,
)

/**
 * 终止原因。
 */
@Serializable
enum class StopReason {
    BUDGET_EXHAUSTED,
    EARLY_STOP_NO_IMPROVE,
    TARGET_SCORE_REACHED,
    OPTIMIZER_CONVERGED,
}

/**
 * 一次调优运行的最终结果。
 *
 * 任何 [Optimizer] 实现都产出同一份结果结构，方便 agent 统一读取。
 *
 * @property best        最优试探记录。
 * @property trace       全部试探轨迹（按迭代顺序）。
 * @property stopReason  终止原因，便于诊断"为什么停在这里"。
 * @property optimizerName 优化器实现名（如 "nelder-mead", "hill-climbing"）。
 */
@Serializable
data class TuningResult(
    val best: TrialRecord,
    val trace: List<TrialRecord>,
    val stopReason: StopReason,
    val optimizerName: String,
) {

    /**
     * 把结果落到 [ctx] 工作区下的 `tuner/{runId}/` 目录。
     *
     * - `result.json`：完整结果（含轨迹）
     * - `trace.csv`：扁平化的试探明细，便于绘图/筛选
     */
    fun writeTo(ctx: ResearchContext, subdir: String = "tuner/${ctx.runId}"): Path {
        val dir = ctx.resolve("$subdir/.placeholder").parent ?: error("无法解析 tuner 目录")
        Files.createDirectories(dir)

        val resultJson = dir.resolve("result.json")
        Files.writeString(
            resultJson,
            JSON.encodeToString(serializer(), this),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        val traceCsv = dir.resolve("trace.csv")
        val paramKeys = trace.flatMap { it.params.keys }.distinct().sorted()
        val header = listOf("iter", "score", "qualified", "took_ms") + paramKeys + listOf("detail")
        val rows = trace.map { record ->
            listOf(
                record.iter.toString(),
                record.observation.score.toString(),
                record.observation.qualified.toString(),
                record.tookMillis.toString(),
            ) + paramKeys.map { key -> record.params[key] ?: "" } + listOf(escapeCsv(record.observation.detail))
        }
        val csv = buildString {
            appendLine(header.joinToString(","))
            for (row in rows) appendLine(row.joinToString(","))
        }
        Files.writeString(
            traceCsv,
            csv,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        return dir
    }

    companion object {
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        private fun escapeCsv(value: String): String =
            if (value.contains(',') || value.contains('"') || value.contains('\n')) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else value
    }
}

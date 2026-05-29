package org.shiroumi.strategy.research.topic.factor.eval

import kotlinx.serialization.Serializable

/**
 * 一轮研究结果的**整理与汇总** —— research 第二部分（评估）的输出态。
 *
 * [ResonanceEvaluator] 逐张裁判后，这里把一批 [CardEvaluation] 聚合成客观、可比对的统计，
 * 供 autoresearch 跨轮比对（这轮比上轮好没好）与最终报告（手册 §13）使用。
 *
 * 它只整理客观事实（各档计数、各硬条件的失败频次、按因子/频带分布），不做主观解读。
 *
 * @property total           评估的卡片总数
 * @property qualifiedCount   A 类（qualified=true）数 —— 与 Metric 脚本口径一致
 * @property levelCounts      A/B/C/Reject 各档计数
 * @property gateFailureCount 每条硬条件（gate 编号）的失败次数，定位最普遍的卡点
 * @property byFactor         按因子名的合格数分布
 * @property byBand           按频带的合格数分布
 */
@Serializable
data class EvaluationReport(
    val total: Int,
    val qualifiedCount: Int,
    val levelCounts: Map<String, Int>,
    val gateFailureCount: Map<Int, Int>,
    val byFactor: Map<String, Int>,
    val byBand: Map<String, Int>,
) {
    companion object {
        /** 把一批逐卡评估聚合成一份整理报告。 */
        fun of(evaluations: List<CardEvaluation>): EvaluationReport {
            val qualified = evaluations.filter { it.qualified }
            return EvaluationReport(
                total = evaluations.size,
                qualifiedCount = qualified.size,
                levelCounts = evaluations.groupingBy { it.conclusionLevel }.eachCount(),
                gateFailureCount = evaluations
                    .flatMap { it.failedGates }
                    .groupingBy { it }.eachCount()
                    .toSortedMap(),
                byFactor = qualified
                    .groupingBy { it.identityFile.substringBefore("__") }.eachCount(),
                byBand = qualified
                    .groupingBy { bandOf(it.identityFile) }.eachCount(),
            )
        }

        /** 从文件名 `{factor}__{Y}__h{horizon}__{band}__{state_id}.json` 取频带段。 */
        private fun bandOf(fileName: String): String =
            fileName.split("__").getOrElse(3) { "?" }
    }
}

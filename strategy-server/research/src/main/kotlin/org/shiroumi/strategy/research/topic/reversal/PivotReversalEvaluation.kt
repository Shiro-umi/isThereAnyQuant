package org.shiroumi.strategy.research.topic.reversal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchEvaluation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.round

/**
 * 反转研究的 Evaluation（②裁判，落盘）。
 *
 * 用户钉死的验收口径（2026-05-29）：「判别力 = 精准预判，误判或弃权也算错误」。
 * 翻译为硬条件（test 集，杜绝训练集自夸）：
 * - **达标线**：test 集在最优 F1 阈值下 precision ≥ 0.80 **且** recall ≥ 0.80
 *   —— 误判（fp）压低 precision、弃权=漏报（fn）压低 recall，两者必须同时高，无取巧空间。
 * - 同时报告 ROC-AUC（阈值无关排序质量，§7.1 L1 目标 ≥0.90）与体检 B 单因子领先力。
 *
 * 验收硬线（§5.3）：若融合 test AUC 贴 0.5、或单因子滞后 AUC 全无信息量，则「t−1 预测 t」不成立，
 * 裁判输出 `RETREAT`，诚实回退到「同步识别 + 盘中预警」的较弱目标——不强行包装成次日预测。
 *
 * 裁判结论本身不作可复用产物（架构纪律），但落盘 JSON + 可读报告供研究迭代追溯，并打印关键数字到 stdout。
 */
class PivotReversalEvaluation(
    /** 方向标签（top/bottom），用于落盘文件名与 stdout 前缀区分双向。 */
    private val tag: String = "top",
    private val precisionGate: Double = 0.80,
    private val recallGate: Double = 0.80,
    private val aucFloor: Double = 0.55,
    // NaN 在研究报告里是合法语义（某因子在窗口内无离散度 → AUC 不可计算），如实保留而非抛异常。
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true },
) : ResearchEvaluation<PivotReversalReport, PivotReversalEvaluation.Verdict> {

    override val name: String = "eval:pivot-reversal-precision-recall-gate"

    @Serializable
    data class Verdict(
        val grade: String,                 // PASS / BELOW_TARGET / RETREAT
        val rationale: String,
        val precisionGate: Double,
        val recallGate: Double,
        val testAuc: Double,
        val testPrecision: Double,
        val testRecall: Double,
        val testF1: Double,
        val testBalancedHit: Double,
        val volumeIncrementOverPrice: Double,   // fusion.test.auc − priceBaseline.test.auc，量价增量
        val report: ReportDump,
    )

    @Serializable
    data class ReportDump(
        val samples: Int,
        val positives: Int,
        val positiveRate: Double,
        val singleFactor: List<FactorDump>,
        val fusionTrain: SplitDump,
        val fusionValidation: SplitDump,
        val fusionTest: SplitDump,
        val priceBaselineTest: SplitDump,
    )

    @Serializable
    data class FactorDump(val name: String, val auc: Double, val ic: Double)

    @Serializable
    data class SplitDump(
        val coverage: Int, val positives: Int, val auc: Double, val tau: Double,
        val precision: Double, val recall: Double, val f1: Double, val balancedHit: Double,
    )

    override fun run(ctx: ResearchContext, input: PivotReversalReport): Verdict {
        val test = input.fusion.test
        val maxSingleAuc = input.singleFactor.maxOfOrNull { if (it.auc.isNaN()) 0.0 else it.auc } ?: 0.0
        val increment = safe(test.auc) - safe(input.priceBaselineTest.auc)

        val grade: String
        val rationale: String
        when {
            test.coverage == 0 || test.auc.isNaN() -> {
                grade = "RETREAT"
                rationale = "test 集无有效样本或 AUC 不可计算：样本量不足，无法验收次日预测。"
            }
            test.auc < aucFloor && maxSingleAuc < aucFloor -> {
                grade = "RETREAT"
                rationale = "融合 test AUC=${pct(test.auc)} 贴近随机、单因子最强滞后 AUC=${pct(maxSingleAuc)} 也无信息量；" +
                    "「t−1 预测 t」不成立，按 §5.3 验收硬线诚实回退到同步识别 + 盘中预警的较弱目标。"
            }
            test.precision >= precisionGate && test.recall >= recallGate -> {
                grade = "PASS"
                rationale = "test 集 precision=${pct(test.precision)} ≥ ${pct(precisionGate)} 且 recall=${pct(test.recall)} ≥ ${pct(recallGate)}；" +
                    "误判与弃权双侧均达标，满足用户「精准预判」口径。"
            }
            else -> {
                grade = "BELOW_TARGET"
                rationale = "test 集有判别力（AUC=${pct(test.auc)}）但未达 80% 双侧门：precision=${pct(test.precision)}、" +
                    "recall=${pct(test.recall)}。需继续调优权重/阈值或承认能力边界。"
            }
        }

        val verdict = Verdict(
            grade = grade,
            rationale = rationale,
            precisionGate = precisionGate,
            recallGate = recallGate,
            testAuc = round4(test.auc),
            testPrecision = round4(test.precision),
            testRecall = round4(test.recall),
            testF1 = round4(test.f1),
            testBalancedHit = round4(test.balancedHit),
            volumeIncrementOverPrice = round4(increment),
            report = dump(input),
        )

        writeArtifacts(ctx, verdict)
        printSummary(verdict)
        return verdict
    }

    private fun writeArtifacts(ctx: ResearchContext, v: Verdict) {
        val dir: Path = ctx.resolve("out/pivot_reversal/.keep").parent
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("verdict_${tag}_${ctx.runId}.json"), json.encodeToString(v))
        Files.writeString(dir.resolve("report_${tag}_${ctx.runId}.txt"), humanReport(v))
    }

    private fun printSummary(v: Verdict) {
        println("pivot_reversal[$tag]_grade=${v.grade}")
        println("pivot_reversal[$tag]_test_auc=${v.testAuc} precision=${v.testPrecision} recall=${v.testRecall} f1=${v.testF1}")
        println("pivot_reversal[$tag]_increment_auc=${v.volumeIncrementOverPrice}")
        println("pivot_reversal[$tag]_rationale=${v.rationale}")
    }

    private fun humanReport(v: Verdict): String = buildString {
        appendLine("次日顶部反转预测 · 验收报告")
        appendLine("================================")
        appendLine("裁定：${v.grade}")
        appendLine("依据：${v.rationale}")
        appendLine()
        val r = v.report
        appendLine("样本：${r.samples}（反转日 ${r.positives}，占比 ${pct(r.positiveRate)}）")
        appendLine()
        appendLine("体检 B · 单因子 t−1 滞后判别力（AUC / Spearman IC）")
        r.singleFactor.sortedByDescending { it.auc }.forEach {
            appendLine("  ${it.name.padEnd(18)} AUC=${pct(it.auc)}  IC=${fmt(it.ic)}")
        }
        appendLine()
        appendLine("融合公式 P^rev 判别力（最优 F1 阈值下裁决）")
        appendLine("  train: " + splitLine(r.fusionTrain))
        appendLine("  val  : " + splitLine(r.fusionValidation))
        appendLine("  test : " + splitLine(r.fusionTest))
        appendLine()
        appendLine("§3.6 消融（test）：纯价基线 vs 融合")
        appendLine("  纯价基线 test: " + splitLine(r.priceBaselineTest))
        appendLine("  融合     test: " + splitLine(r.fusionTest))
        appendLine("  量价增量 ΔAUC = ${fmt(v.volumeIncrementOverPrice)}")
    }

    private fun splitLine(s: SplitDump): String =
        "AUC=${pct(s.auc)} τ=${fmt(s.tau)} P=${pct(s.precision)} R=${pct(s.recall)} F1=${pct(s.f1)} balHit=${pct(s.balancedHit)} (n=${s.coverage}, pos=${s.positives})"

    private fun dump(r: PivotReversalReport) = ReportDump(
        samples = r.samples, positives = r.positives, positiveRate = round4(r.positiveRate),
        singleFactor = r.singleFactor.map { FactorDump(it.name, round4(it.auc), round4(it.ic)) },
        fusionTrain = dumpSplit(r.fusion.train),
        fusionValidation = dumpSplit(r.fusion.validation),
        fusionTest = dumpSplit(r.fusion.test),
        priceBaselineTest = dumpSplit(r.priceBaselineTest),
    )

    private fun dumpSplit(s: PivotReversalReport.SplitMetrics) = SplitDump(
        coverage = s.coverage, positives = s.positives, auc = round4(s.auc), tau = round4(s.tau),
        precision = round4(s.precision), recall = round4(s.recall), f1 = round4(s.f1), balancedHit = round4(s.balancedHit),
    )

    private fun safe(x: Double) = if (x.isNaN()) 0.0 else x
    private fun round4(x: Double): Double = if (x.isNaN()) Double.NaN else round(x * 10000) / 10000.0
    private fun pct(x: Double): String = if (x.isNaN()) "NaN" else "${round(x * 1000) / 10.0}%"
    private fun fmt(x: Double): String = if (x.isNaN()) "NaN" else "${round(x * 1000) / 1000.0}"
}

package org.shiroumi.strategy.research.topic.reversal

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStudy

/**
 * 反转研究的 Study（①算度量，不裁判）—— 次日顶部反转预测。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §3.6 / §5.3 / §7。
 *
 * 数据底座统一交给 [PivotReversalDataset]（取数 + trend score + VP 推导 + 样本对齐，全部市场级、严格 t−1 → t）。
 * 本 Study 只在装配好的样本上算诊断度量：
 * - 体检 B：逐单因子 t−1 滞后 AUC/IC（看单因子有没有领先信息量、符号是否与文档先验一致）。
 * - 融合公式 [PivotReversalScorer] → P^rev 的 train/val/test 判别力（AUC + 最优 F1 阈值裁决）。
 * - §3.6 消融：纯价基线（μ=κ=0）的 test 判别力，与融合对比验证量价增量。
 *
 * Study 只产出 [PivotReversalReport]（未裁判度量），合格与否交 [PivotReversalEvaluation]。
 */
class PivotReversalStudy(
    private val params: PivotReversalScorer.Params = PivotReversalScorer.Params(),
    /** 研究方向：顶反 / 底反。 */
    private val direction: PivotReversalFeatures.Direction = PivotReversalFeatures.Direction.TOP,
    /** 可选预装配数据集；null 时从 DB 取数。调优后复跑诊断可传入缓存集避免重复触库。 */
    private val preloaded: PivotReversalDataset? = null,
) : ResearchStudy<Unit, PivotReversalReport> {

    override val name: String = "study:pivot-reversal-${direction.name.lowercase()}"

    override fun run(ctx: ResearchContext, input: Unit): PivotReversalReport {
        val dataset = preloaded ?: PivotReversalDataset.load(ctx, direction)
        return diagnose(dataset, params)
    }

    /** 在给定数据集 + 参数上算完整诊断报告（纯内存，测试可直接调用）。 */
    fun diagnose(dataset: PivotReversalDataset, scorerParams: PivotReversalScorer.Params): PivotReversalReport {
        val samples = dataset.samples
        if (samples.size < MIN_SAMPLES) return emptyReport(samples.size)

        val labels = dataset.labels

        // —— 体检 B：逐单因子滞后判别力（核心 11 因子 + 上游情绪因子层附加因子）——
        val coreDiag = SINGLE_FACTORS.map { (nm, extract) ->
            val s = DoubleArray(samples.size) { extract(samples[it]) }
            PivotReversalReport.FactorDiag(nm, PivotMetrics.rocAuc(s, labels), PivotMetrics.spearmanIc(s, labels))
        }
        val extraKeys = samples.first().extra.keys
        val extraDiag = extraKeys.map { key ->
            val s = DoubleArray(samples.size) { samples[it].extra[key] ?: 0.0 }
            PivotReversalReport.FactorDiag("sent:$key", PivotMetrics.rocAuc(s, labels), PivotMetrics.spearmanIc(s, labels))
        }
        val singleFactor = coreDiag + extraDiag

        val train = dataset.train
        val fusion = PivotReversalReport.SplitDiag(
            train = diagOf(PivotReversalScorer(scorerParams), train, train),
            validation = diagOf(PivotReversalScorer(scorerParams), train, dataset.validation),
            test = diagOf(PivotReversalScorer(scorerParams), train, dataset.test),
        )
        val priceParams = scorerParams.copy(priceOnly = true)
        val priceBaselineTest = diagOf(PivotReversalScorer(priceParams), train, dataset.test)

        return PivotReversalReport(
            samples = samples.size,
            positives = dataset.positives,
            positiveRate = dataset.positives.toDouble() / samples.size,
            singleFactor = singleFactor,
            fusion = fusion,
            priceBaselineTest = priceBaselineTest,
        )
    }

    /**
     * 在某切分上算融合公式的判别力：用 [fitOn] 拟合 ECDF（始终训练集，杜绝泄漏），对 [evalOn] 打分，
     * 算 AUC + 在该集上的最优 F1 阈值裁决（balancedHit 用同阈值读出）。
     */
    private fun diagOf(
        scorer: PivotReversalScorer,
        fitOn: List<PivotReversalFeatures.Sample>,
        evalOn: List<PivotReversalFeatures.Sample>,
    ): PivotReversalReport.SplitMetrics {
        if (evalOn.isEmpty()) return EMPTY_SPLIT
        val ecdf = PivotReversalScorer.fitEcdf(scorer.rawAll(fitOn))
        val scores = DoubleArray(evalOn.size) { ecdf(scorer.raw(evalOn[it])) }
        val labels = IntArray(evalOn.size) { evalOn[it].label }
        val auc = PivotMetrics.rocAuc(scores, labels)
        val (tau, conf) = PivotMetrics.bestThreshold(scores, labels) { it.f1 }
        return PivotReversalReport.SplitMetrics(
            coverage = evalOn.size,
            positives = labels.count { it == 1 },
            auc = auc,
            tau = tau,
            precision = conf.precision,
            recall = conf.recall,
            f1 = conf.f1,
            balancedHit = conf.balancedHit,
        )
    }

    private fun emptyReport(n: Int) = PivotReversalReport(
        samples = n, positives = 0, positiveRate = 0.0,
        singleFactor = emptyList(),
        fusion = PivotReversalReport.SplitDiag(EMPTY_SPLIT, EMPTY_SPLIT, EMPTY_SPLIT),
        priceBaselineTest = EMPTY_SPLIT,
    )

    companion object {
        private const val MIN_SAMPLES = 120

        private val SINGLE_FACTORS: List<Pair<String, (PivotReversalFeatures.Sample) -> Double>> = listOf(
            "z(-R_intra)" to { s -> s.zNegRIntra },
            "z(0.5-P_close)" to { s -> s.zPcloseGap },
            "z(S_upper)" to { s -> s.zSUpper },
            "z(D_oc)" to { s -> s.zDoc },
            "Delta_intra(neg)" to { s -> -s.deltaIntra },
            "z(VP1)" to { s -> s.zVp1 },
            "z(-VP2c)" to { s -> s.zNegVp2c },
            "z(VP2v)" to { s -> s.zVp2v },
            "z(-VPbeta)" to { s -> s.zNegVpBeta },
            "z(vdot)" to { s -> s.zVdot },
            "trendScore" to { s -> s.trendScore },
        )

        private val EMPTY_SPLIT = PivotReversalReport.SplitMetrics(
            coverage = 0, positives = 0, auc = Double.NaN, tau = 0.0,
            precision = 0.0, recall = 0.0, f1 = 0.0, balancedHit = 0.0,
        )
    }
}

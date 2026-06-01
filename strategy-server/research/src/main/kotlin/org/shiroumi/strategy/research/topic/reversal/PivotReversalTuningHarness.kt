package org.shiroumi.strategy.research.topic.reversal

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousDim
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousSpace
import org.shiroumi.strategy.research.tuner.blackbox.NelderMeadOptimizer
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget

/**
 * 反转融合公式的黑盒调优 harness —— 复用 research 黑盒 tuner（NelderMead），不新造轮子。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §6/§7 + 体检 B 实测结论。
 *
 * 关键决策（由体检 B 数据驱动，见记忆 pivot-reversal-sync-vs-lead）：
 * - **搜索空间全维度跨零**：体检 B 显示 trendScore 等因子对次日反转是**强反向**信号（文档先验符号反了），
 *   故所有权重 w/θ/μ/λ 与背离锐度 β 都允许取负，让数据通过参数符号说话，而非钉死文档先验号。
 * - **目标 = 训练集 ROC-AUC**：阈值无关、对极不平衡稳健，直接量化「判别力」（用户口径「精准预判」的排序基础）；
 *   用验证集 AUC 作过拟合哨兵（落入 detail，不直接驱动优化）。
 * - **数据装配一次、内存反复打分**：[PivotReversalDataset] 缓存样本，每个 trial 只换 [PivotReversalScorer.Params]
 *   重打分，不重复触库——参数搜索可行的性能前提。
 */
class PivotReversalTuningHarness(
    private val dataset: PivotReversalDataset,
    /** 纳入公式的情绪因子个数（按全样本 |IC| 降序选 top-K，含交互/微分/长窗形态项）。 */
    private val topK: Int = 28,
) {

    /** 按 |Spearman IC| 降序选出的 top-K 情绪因子 key（金矿优先进公式）。 */
    val topSentiment: List<String> by lazy {
        val keys = dataset.samples.firstOrNull()?.extra?.keys ?: emptySet()
        val labels = dataset.labels
        keys.map { k ->
            val s = DoubleArray(dataset.samples.size) { dataset.samples[it].extra[k] ?: 0.0 }
            k to PivotMetrics.spearmanIc(s, labels)
        }.filter { !it.second.isNaN() }
            .sortedByDescending { kotlin.math.abs(it.second) }
            .take(topK)
            .map { it.first }
    }

    /**
     * 搜索空间。**聚焦金矿、剔除噪声维度**（实测量价 θ/μ/κ 对反转标签近零 → 不进搜索，固定为 0）：
     * 价形态 w1..w4 + 恶化 γ/λ + 背离锐度 β + trendScore 线性 wTrend + top-K 情绪因子权重。全维度跨零，让数据学符号。
     */
    fun buildSearchSpace(): ContinuousSpace = ContinuousSpace(
        buildList {
            add(dim("w1", 1.0)); add(dim("w2", 1.0)); add(dim("w3", 1.0)); add(dim("w4", 1.0))
            add(ContinuousDim("gamma", 0.2, 3.0, 1.0))
            add(dim("lambda", 1.0))
            add(dim("beta", 2.0))
            add(dim("wTrend", 0.0))   // trendScore 强反向 → 期望学成负
            topSentiment.forEach { add(dim("sent_$it", 0.0)) }
        },
    )

    private fun dim(key: String, initial: Double) = ContinuousDim(key, -5.0, 5.0, initial)

    /** 默认目标函数：在全数据集的 train/val 切分上闭合（固定切分调优用）。 */
    fun objective(): ObjectiveFunction = objectiveOn(dataset.train, dataset.validation)

    /**
     * 在任意 [train]/[validation] 子集上闭合的目标函数：最大化 train AUC（方向由 ECDF 单调性 + raw 符号决定）。
     * walk-forward 每个滚动窗用当前训练子集构造此目标，不碰未来样本。
     */
    fun objectiveOn(
        train: List<PivotReversalFeatures.Sample>,
        validation: List<PivotReversalFeatures.Sample>,
    ): ObjectiveFunction = ObjectiveFunction { _, params ->
        val scorer = PivotReversalScorer(decode(params))
        val ecdf = PivotReversalScorer.fitEcdf(scorer.rawAll(train))
        val trainScores = DoubleArray(train.size) { ecdf(scorer.raw(train[it])) }
        val trainLabels = IntArray(train.size) { train[it].label }
        val trainAuc = PivotMetrics.rocAuc(trainScores, trainLabels)
        // loss 直接对齐实战工作点：最优 F0.5（β=0.5 偏精度，大阴线预警要准），辅以小权重 AUC 平滑搜索面、
        // 避免阶跃量梯度噪声把 NelderMead 卡死。
        val (_, conf) = PivotMetrics.bestThreshold(trainScores, trainLabels) { it.fBeta(0.5) }
        val f05 = conf.fBeta(0.5)
        val aucTerm = if (trainAuc.isNaN()) 0.0 else trainAuc
        val score = if (f05.isNaN()) Double.NEGATIVE_INFINITY else f05 + 0.1 * aucTerm
        val valAuc = aucOf(scorer, ecdf, validation)
        Observation(
            score = score,
            qualified = !valAuc.isNaN() && valAuc >= 0.6,
            detail = "trainF0.5=${fmt(f05)} trainP=${fmt(conf.precision)} trainR=${fmt(conf.recall)} valAuc=${fmt(valAuc)}",
        )
    }

    /** 跑 NelderMead 搜索，返回最优参数（已解码）+ 原始 TuningResult。 */
    fun tune(ctx: ResearchContext, maxIter: Int = 400): Pair<PivotReversalScorer.Params, org.shiroumi.strategy.research.tuner.core.TuningResult> {
        val space = buildSearchSpace()
        val budget = TuningBudget(maxIter = maxIter, patience = 60, minDelta = 1e-5)
        val optimizer = NelderMeadOptimizer(space, objective(), budget, initialStep = 0.5)
        val result = optimizer.optimize(ctx)
        val bestParams = decode(result.best.params)
        result.writeTo(ctx)
        return bestParams to result
    }

    /** 在给定训练子集上跑 NelderMead 调优，返回最优参数（walk-forward 各滚动窗复用）。 */
    fun tuneOn(
        ctx: ResearchContext,
        train: List<PivotReversalFeatures.Sample>,
        validation: List<PivotReversalFeatures.Sample>,
        maxIter: Int,
    ): PivotReversalScorer.Params {
        val budget = TuningBudget(maxIter = maxIter, patience = 40, minDelta = 1e-5)
        val optimizer = NelderMeadOptimizer(buildSearchSpace(), objectiveOn(train, validation), budget, initialStep = 0.5)
        return decode(optimizer.optimize(ctx).best.params)
    }

    // ── 解码 / 度量 ──

    private fun decode(params: Map<String, String>): PivotReversalScorer.Params {
        fun g(k: String, d: Double) = params[k]?.toDoubleOrNull() ?: d
        val sentWeights = topSentiment.associateWith { g("sent_$it", 0.0) }
        return PivotReversalScorer.Params(
            w1 = g("w1", 1.0), w2 = g("w2", 1.0), w3 = g("w3", 1.0), w4 = g("w4", 1.0),
            gamma = g("gamma", 1.0).coerceAtLeast(0.05),
            beta = g("beta", 2.0),
            lambda = g("lambda", 1.0),
            // 量价项剔除（实测对反转标签近零，避免过拟合噪声）：μ=0 关掉量能领先与门控。
            theta1 = 0.0, theta2 = 0.0, theta3 = 0.0, theta4 = 0.0,
            mu = 0.0, kappa = 0.0, rho = 1.0, vStar = 0.0,
            wTrend = g("wTrend", 0.0),
            sentimentWeights = sentWeights,
            priceOnly = false,
        )
    }

    private fun aucOf(
        scorer: PivotReversalScorer,
        ecdf: (Double) -> Double,
        on: List<PivotReversalFeatures.Sample>,
    ): Double {
        if (on.isEmpty()) return Double.NaN
        val scores = DoubleArray(on.size) { ecdf(scorer.raw(on[it])) }
        val labels = IntArray(on.size) { on[it].label }
        return PivotMetrics.rocAuc(scores, labels)
    }

    private fun fmt(x: Double) = if (x.isNaN()) "NaN" else "${kotlin.math.round(x * 1000) / 1000.0}"
}

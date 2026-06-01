package org.shiroumi.strategy.research.topic.reversal

import org.shiroumi.strategy.research.pipeline.ResearchContext

/**
 * Walk-forward 滚动验证 —— 反转预测泛化能力的真实检验（用户选定 2026-05-30）。
 *
 * 动机：固定 train/val/test 切分让 test 落入单一近期 regime，且小样本噪声大。walk-forward 用
 * **anchored 扩展训练窗**滚动前进：每一步用「截至当前」的全部历史训练（调优权重 + 拟合 ECDF），
 * 预测紧邻的下一段（严格样本外、零未来信息），把**全程每一段的样本外预测拼接**起来算整体判别力。
 * 这样：① test = 跨 2000-2026 多轮牛熊的样本外拼接，真实反映泛化；② 用上全部正类（不止固定 test 的几十个）；
 * ③ 权重周期性重调，自适应 regime 变化。
 *
 * 防泄漏：训练窗严格 [0, cut)，预测窗 [cut, cut+step)；特征 rolling-z 在装配时已是扩张窗（只用前序）；
 * ECDF 仅用训练窗拟合。每一段预测时，模型只见过该段之前的样本。
 */
class PivotWalkForward(
    private val harness: PivotReversalTuningHarness,
    private val samples: List<PivotReversalFeatures.Sample>,
    /** 初始训练窗样本数：至少积累这么多历史才开始滚动预测。 */
    private val initTrain: Int,
    /** 滚动步长（样本数）：每段预测多少个、随后并入训练窗。 */
    private val step: Int,
    /** 每隔多少步重调一次权重（其间复用上次权重，只随训练窗扩大重拟合 ECDF）。控制 NelderMead 调用次数。 */
    private val rebalanceEvery: Int,
    /** 每次重调的 NelderMead 迭代预算（walk-forward 调用频繁，单次精简）。 */
    private val tuneIter: Int,
) {

    data class Result(
        val oosScores: DoubleArray,
        val oosLabels: IntArray,
        val oosTrend: DoubleArray,
        val foldCount: Int,
        val auc: Double,
        val tau: Double,
        val precision: Double,
        val recall: Double,
        val f1: Double,
        val balancedHit: Double,
    )

    fun run(ctx: ResearchContext): Result {
        val oosScores = ArrayList<Double>()
        val oosLabels = ArrayList<Int>()
        val oosTrend = ArrayList<Double>()   // 每个 OOS 样本的 t−1 情绪亢奋度 P^up，供条件分层
        var folds = 0
        var params: PivotReversalScorer.Params? = null

        var cut = initTrain
        var sinceRebalance = rebalanceEvery   // 首段强制先调一次
        while (cut < samples.size) {
            val trainWin = samples.subList(0, cut)
            val end = minOf(cut + step, samples.size)
            val predictWin = samples.subList(cut, end)

            // 周期性重调权重：用「内层时序末段」做验证哨兵（训练窗尾部 20%），其余训练。
            if (sinceRebalance >= rebalanceEvery) {
                val innerValStart = (trainWin.size * 0.8).toInt()
                val innerTrain = trainWin.subList(0, innerValStart)
                val innerVal = trainWin.subList(innerValStart, trainWin.size)
                if (innerTrain.size >= MIN_INNER_TRAIN && innerVal.isNotEmpty()) {
                    params = harness.tuneOn(ctx, innerTrain, innerVal, tuneIter)
                }
                sinceRebalance = 0
            }
            val p = params ?: PivotReversalScorer.Params()

            // 用整段训练窗拟合 ECDF（样本外预测的概率连接基准），对预测窗逐样本打分。
            val scorer = PivotReversalScorer(p)
            val ecdf = PivotReversalScorer.fitEcdf(scorer.rawAll(trainWin))
            for (s in predictWin) {
                oosScores += ecdf(scorer.raw(s))
                oosLabels += s.label
                oosTrend += s.trendScore
            }
            folds++
            sinceRebalance += 1
            cut = end
        }

        val scores = oosScores.toDoubleArray()
        val labels = oosLabels.toIntArray()
        val auc = PivotMetrics.rocAuc(scores, labels)
        val (tau, conf) = PivotMetrics.bestThreshold(scores, labels) { it.f1 }
        return Result(
            oosScores = scores, oosLabels = labels, oosTrend = oosTrend.toDoubleArray(), foldCount = folds,
            auc = auc, tau = tau,
            precision = conf.precision, recall = conf.recall, f1 = conf.f1, balancedHit = conf.balancedHit,
        )
    }

    companion object {
        private const val MIN_INNER_TRAIN = 200
    }
}

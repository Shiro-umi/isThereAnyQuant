package org.shiroumi.strategy.research.topic.crashstock

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.topic.reversal.PivotMetrics

/**
 * 个股截面下跌预警的 **Walk-Forward 训练 + 样本外评估** 驱动器。
 *
 * 滚动：样本按截面(date)升序分组；训练窗 [w, w+trainCross) 训练可微模型（梯度下降），
 * 测试窗 [w+trainCross, w+trainCross+testCross) 用学到的权重打「未来下跌概率」分，
 * 累计测试集到全局 OOS 池。窗口滚动 testCross。训练/测试严格不重叠且训练在前。
 *
 * 评估两套口径并列：
 *   - 全市场：所有测试样本上的 AUC / recall60工作点precision；
 *   - 可投资域：仅 invest=true 样本上的同口径——看可学软门控 + 域过滤是否真提纯（这才是实战口径）。
 *
 * 召回率是一等公民（用户「通用预警、召回关键」）：报告 recall≥0.6 工作点的 precision，以及 AUC（整体判别力）。
 */
class PivotCrashStockHarness(
    private val trainCross: Int = 160,
    private val testCross: Int = 40,
    private val lossKind: PivotCrashStockModel.LossKind = PivotCrashStockModel.LossKind.SOFT_FBETA,
    private val fBeta: Double = 2.0,
    private val labelKind: PivotCrashStockSample.LabelKind = PivotCrashStockSample.LabelKind.REL,
    private val dropLevel: Boolean = false,
    private val useGate: Boolean = true,
    private val l2: Double = 0.01,
    private val maxIter: Int = 300,
    private val learningRate: Float = 5e-2f,
) {

    data class SplitEval(
        val coverage: Int, val positives: Int, val posRate: Double,
        val auc: Double,
        val precAtR60: Double, val recAtR60: Double, val tauR60: Double,
        val precAtBestF1: Double, val recAtBestF1: Double, val f1: Double,
    )

    data class Result(
        val oosSamples: Int,
        val full: SplitEval,
        val investable: SplitEval,
        val avgGateTau: Double,
        val avgGateGamma: Double,
        val avgWeights: DoubleArray,
        val windows: Int,
    )

    fun run(ctx: ResearchContext, allSamples: List<PivotCrashStockSample.Sample>): Result {
        // 按截面分组（date 升序）
        val byCross = allSamples.groupBy { it.tradeDate }.toSortedMap()
        val crossList = byCross.entries.toList()   // 升序
        println("总截面数：${crossList.size}，总样本：${allSamples.size}")

        // OOS 池
        val oosScore = ArrayList<Double>(); val oosLabel = ArrayList<Int>(); val oosInvest = ArrayList<Boolean>()
        var sumTau = 0.0; var sumGamma = 0.0; var windows = 0
        val sumW = DoubleArray(PivotCrashStockSample.NF)

        var w = 0
        while (w + trainCross + testCross <= crossList.size) {
            val trainSamples = (w until w + trainCross).flatMap { crossList[it].value }
            val testCrosses = (w + trainCross until w + trainCross + testCross).map { crossList[it] }
            // 训练集需有足够正例，否则跳过
            val trainPos = trainSamples.count { it.labelBy(labelKind) == 1 }
            if (trainSamples.size < 500 || trainPos < 20) { w += testCross; continue }

            val model = PivotCrashStockModel(
                samples = trainSamples, l2 = l2, useGate = useGate,
                lossKind = lossKind, fBeta = fBeta, labelKind = labelKind, dropLevel = dropLevel,
            )
            model.train(maxIter = maxIter, learningRate = learningRate.toDouble(), patience = 30)

            val (liqMean, liqSd) = model.liqMeanSd
            for ((_, samples) in testCrosses) {
                for (s in samples) {
                    val liqZ = (s.liquidity - liqMean) / liqSd
                    val score = model.scoreOf(s.features, liqZ)
                    oosScore.add(score); oosLabel.add(s.labelBy(labelKind)); oosInvest.add(s.invest)
                }
            }
            sumTau += model.bestGateTau; sumGamma += model.bestGateGamma; windows++
            for (j in 0 until PivotCrashStockSample.NF) sumW[j] += model.bestWeights[j]
            w += testCross
        }

        require(windows > 0) { "无有效 walk-forward 窗口（样本/正例不足）" }
        val full = evalSplit(oosScore, oosLabel, oosInvest, investOnly = false)
        val investable = evalSplit(oosScore, oosLabel, oosInvest, investOnly = true)
        return Result(
            oosSamples = oosScore.size, full = full, investable = investable,
            avgGateTau = sumTau / windows, avgGateGamma = sumGamma / windows,
            avgWeights = DoubleArray(PivotCrashStockSample.NF) { sumW[it] / windows },
            windows = windows,
        )
    }

    /**
     * O(n log n) 单遍扫描评估（替代 PivotMetrics 的 O(n²) 阈值搜索）。
     *
     * 个股截面 OOS 池有数十万~百万样本，逐阈值重扫全样本（O(n²)）会卡死。
     * 这里按分数降序排一次，单遍累积 TP/FP，同时算出：recall≥0.6 最高阈值工作点、最优 F1 工作点、ROC-AUC（梯形积分）。
     */
    private fun evalSplit(
        scoreAll: List<Double>, labelAll: List<Int>, investAll: List<Boolean>, investOnly: Boolean,
    ): SplitEval {
        val idx = scoreAll.indices.filter { !investOnly || investAll[it] }
        val n = idx.size
        val pos = idx.count { labelAll[it] == 1 }
        if (n == 0 || pos == 0 || pos == n) {
            return SplitEval(n, pos, if (n == 0) 0.0 else pos.toDouble() / n,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
        val neg = n - pos
        // 按分数降序排（同分聚成一段，阈值在段边界处取值）
        val order = idx.sortedByDescending { scoreAll[it] }
        var tp = 0; var fp = 0
        var auc = 0.0; var prevFpr = 0.0; var prevTpr = 0.0
        var bestF1 = -1.0; var f1Prec = 0.0; var f1Rec = 0.0
        var r60Prec = 0.0; var r60Rec = 0.0; var r60Tau = Double.NaN; var r60Found = false
        var i = 0
        while (i < n) {
            val sTau = scoreAll[order[i]]
            // 把所有等于当前分数的样本一次性纳入「预测为正」（阈值=sTau）
            while (i < n && scoreAll[order[i]] == sTau) {
                if (labelAll[order[i]] == 1) tp++ else fp++
                i++
            }
            val recall = tp.toDouble() / pos
            val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            // ROC 梯形积分
            val tpr = recall; val fpr = fp.toDouble() / neg
            auc += (fpr - prevFpr) * (tpr + prevTpr) / 2.0
            prevFpr = fpr; prevTpr = tpr
            // 最优 F1
            val f1 = if (precision + recall <= 0.0) 0.0 else 2 * precision * recall / (precision + recall)
            if (f1 > bestF1) { bestF1 = f1; f1Prec = precision; f1Rec = recall }
            // recall≥0.6 的最高阈值工作点（降序扫，recall 单调升，首次达 0.6 即最高阈值点）
            if (!r60Found && recall >= 0.60) { r60Found = true; r60Prec = precision; r60Rec = recall; r60Tau = sTau }
        }
        return SplitEval(
            coverage = n, positives = pos, posRate = pos.toDouble() / n,
            auc = auc,
            precAtR60 = r60Prec, recAtR60 = r60Rec, tauR60 = r60Tau,
            precAtBestF1 = f1Prec, recAtBestF1 = f1Rec, f1 = bestF1,
        )
    }
}

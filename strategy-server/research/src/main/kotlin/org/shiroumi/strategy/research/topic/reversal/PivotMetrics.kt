package org.shiroumi.strategy.research.topic.reversal

import kotlin.math.sqrt

/**
 * 反转研究的判别力度量 —— 纯函数统计工具（无状态、可独立单测）。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §5.3（体检 B）/ §7.1（L1/L2 指标）。
 *
 * 验收口径（用户钉死，2026-05-29）：「判别力 = 精准预判，误判或弃权也算错误」。
 * 翻译成可计算指标：在工作阈值 τ 下，模型对每个反转日要么命中（对）、要么漏报=弃权（错），
 * 对每个普通日报警=误判（错）。于是「正确率」既不能靠不平衡刷高 accuracy，也不能靠只报极少数刷高 precision——
 * 它要求 **precision 与 recall 同时高**。本工具同时产出 ROC-AUC（阈值无关排序质量）与
 * 在最优 F1 阈值下的 precision / recall / F1，[hitRate] 即「误判+弃权都算错」语义下的综合命中率。
 */
object PivotMetrics {

    /** 一组分数 + 二值标签在某工作阈值下的混淆矩阵裁决。 */
    data class Confusion(val tp: Int, val fp: Int, val fn: Int, val tn: Int) {
        val precision: Double get() = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall: Double get() = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val f1: Double get() {
            val p = precision; val r = recall
            return if (p + r <= 0.0) 0.0 else 2 * p * r / (p + r)
        }

        /**
         * 「误判 + 弃权都算错」口径下的综合命中率（用户钉死口径的直接量化）：
         * 正确 = 命中的反转日(tp) + 正确放过的普通日(tn)，但**弃权（漏报 fn）与误判（fp）都计为错误**。
         * 因极端不平衡下 tn 天然巨大会稀释惩罚，这里用**平衡命中率** = (recall + specificity)/2，
         * 让「抓住反转」与「不乱报」两侧等权——这正是「弃权也算错」要表达的：不能靠放过多数普通日蒙混。
         */
        val balancedHit: Double get() {
            val specificity = if (fp + tn == 0) 0.0 else tn.toDouble() / (fp + tn)
            return (recall + specificity) / 2.0
        }

        /** F_β：β<1 偏精度、β>1 偏召回。大阴线预警要准，调优用 β=0.5（精度权重 4 倍于召回）。 */
        fun fBeta(beta: Double): Double {
            val p = precision; val r = recall
            val b2 = beta * beta
            val denom = b2 * p + r
            return if (denom <= 0.0) 0.0 else (1 + b2) * p * r / denom
        }
    }

    /** ROC-AUC：分数对二值标签的排序质量（Mann–Whitney U / rank-sum，含并列均秩）。返回 NaN 表示单类样本。 */
    fun rocAuc(scores: DoubleArray, labels: IntArray): Double {
        require(scores.size == labels.size) { "scores/labels 长度不一致" }
        val n = scores.size
        val pos = labels.count { it == 1 }
        val neg = n - pos
        if (pos == 0 || neg == 0) return Double.NaN
        // 按分数升序排名（并列取平均秩）
        val idx = (0 until n).sortedBy { scores[it] }
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && scores[idx[j + 1]] == scores[idx[i]]) j++
            val avgRank = (i + j) / 2.0 + 1.0   // 秩从 1 起
            for (kk in i..j) ranks[idx[kk]] = avgRank
            i = j + 1
        }
        var rankSumPos = 0.0
        for (t in 0 until n) if (labels[t] == 1) rankSumPos += ranks[t]
        val u = rankSumPos - pos * (pos + 1) / 2.0
        return u / (pos.toDouble() * neg)
    }

    /**
     * 信息系数 IC：分数与标签的 Spearman 秩相关（对单调变换稳健，符合「排序质量」语义）。
     * 标签为二值时退化为点二列秩相关，仍可读「分数把反转日往高处排」的强度与符号。
     */
    fun spearmanIc(scores: DoubleArray, labels: IntArray): Double {
        val rx = rankOf(scores)
        val ry = rankOf(DoubleArray(labels.size) { labels[it].toDouble() })
        return pearson(rx, ry)
    }

    /** 在阈值 τ 下裁决混淆矩阵（score ≥ τ 判为「预测反转」）。 */
    fun confusionAt(scores: DoubleArray, labels: IntArray, tau: Double): Confusion {
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        for (i in scores.indices) {
            val pred = scores[i] >= tau
            val pos = labels[i] == 1
            when {
                pred && pos -> tp++
                pred && !pos -> fp++
                !pred && pos -> fn++
                else -> tn++
            }
        }
        return Confusion(tp, fp, fn, tn)
    }

    /**
     * 在满足 precision ≥ [minPrecision] 的阈值中，选 recall 最大的工作点（精度优先的实战口径：
     * 预警大阴线要准，先卡精度门、再尽量多覆盖）。无阈值满足时返回精度最高的那个。
     */
    fun thresholdForPrecision(
        scores: DoubleArray,
        labels: IntArray,
        minPrecision: Double,
    ): Pair<Double, Confusion> {
        val candidates = scores.distinct().sorted()
        var bestTau = candidates.lastOrNull() ?: 0.0
        var bestConf = confusionAt(scores, labels, bestTau)
        var bestRecallUnderGate = -1.0
        var fallbackTau = bestTau
        var fallbackConf = bestConf
        var fallbackPrec = -1.0
        for (tau in candidates) {
            val conf = confusionAt(scores, labels, tau)
            if (conf.precision >= minPrecision && conf.recall > bestRecallUnderGate) {
                bestRecallUnderGate = conf.recall; bestTau = tau; bestConf = conf
            }
            if (conf.precision > fallbackPrec) { fallbackPrec = conf.precision; fallbackTau = tau; fallbackConf = conf }
        }
        return if (bestRecallUnderGate >= 0.0) bestTau to bestConf else fallbackTau to fallbackConf
    }

    /**
     * 找满足「召回 ≥ minRecall」的**最高阈值**（即该召回下 precision 最高的工作点）。
     * 召回随阈值降低单调升，故总能达成；返回此时的 precision 体现「为换这个召回付出的精度代价」。
     */
    fun thresholdForRecall(
        scores: DoubleArray,
        labels: IntArray,
        minRecall: Double,
    ): Pair<Double, Confusion> {
        val candidates = scores.distinct().sorted()
        var bestTau = candidates.firstOrNull() ?: 0.0
        var bestConf = confusionAt(scores, labels, bestTau)
        var bestPrecUnderGate = -1.0
        var found = false
        for (tau in candidates) {
            val conf = confusionAt(scores, labels, tau)
            // 满足召回门槛的候选里，取 precision 最高（即阈值最高）的那个。
            if (conf.recall >= minRecall && conf.precision > bestPrecUnderGate) {
                bestPrecUnderGate = conf.precision; bestTau = tau; bestConf = conf; found = true
            }
        }
        return if (found) bestTau to bestConf else bestTau to bestConf
    }

    /** 在所有候选阈值（各样本分数）中，按目标 [objective] 选最优阈值，返回 (τ, confusion)。 */
    fun bestThreshold(
        scores: DoubleArray,
        labels: IntArray,
        objective: (Confusion) -> Double = { it.f1 },
    ): Pair<Double, Confusion> {
        val candidates = scores.distinct().sorted()
        var bestTau = candidates.firstOrNull() ?: 0.0
        var bestConf = confusionAt(scores, labels, bestTau)
        var bestVal = objective(bestConf)
        for (tau in candidates) {
            val conf = confusionAt(scores, labels, tau)
            val v = objective(conf)
            if (v > bestVal) { bestVal = v; bestTau = tau; bestConf = conf }
        }
        return bestTau to bestConf
    }

    // ── 内部统计 ──

    /** 平均秩（并列取均秩），用于 Spearman。 */
    private fun rankOf(x: DoubleArray): DoubleArray {
        val n = x.size
        val idx = (0 until n).sortedBy { x[it] }
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && x[idx[j + 1]] == x[idx[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            for (kk in i..j) ranks[idx[kk]] = avg
            i = j + 1
        }
        return ranks
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        if (x.size != y.size || x.size < 3) return Double.NaN
        val mx = x.average(); val my = y.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in x.indices) {
            val vx = x[i] - mx; val vy = y[i] - my
            num += vx * vy; dx += vx * vx; dy += vy * vy
        }
        val den = sqrt(dx * dy)
        return if (den < 1e-12) Double.NaN else num / den
    }
}

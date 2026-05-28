package org.shiroumi.strategy.research.tuner.sentiment

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 次日情绪加强概率公式的纯 Kotlin 实现。
 *
 * 输入：按 tradeDate 升序排列的 sentiment_factor_daily 记录序列
 * 输出：每个交易日的 score ∈ [0,1]，表示 ΔY(T+1) > 0 的经验概率
 *
 * 公式七阶段流水线完全对应设计文档：
 *   W ← Y 自相关时间
 *   e_k ← ψ_k · μ_k        (38 个单因子能量)
 *   E_X ← 熵权聚合         (五棵树)
 *   E_X.total ← F1b/F2a 频带 SNR 配比
 *   T_temp ← 振幅衰减       (温度调节)
 *   E_sys ← 温度 × Σ 四树  (系统总能量)
 *   S_sys ← Σ ΔE · w       (指数加权累加)
 *   score ← ECDF(S_sys)    (经验分位)
 */
class NextDaySentimentScorer(
    private val theta: ThetaConfig = ThetaConfig.IDENTITY,
    private val minWindow: Int = 40,
    private val maxWindow: Int = 120,
    /** 固定窗口长度，> 0 时覆盖自适应窗口计算 */
    private val fixedWindow: Int = 0,
) {
    // ---- 因子分组（v2 · D 树合并至 A 树三层） ----
    private val A_LEVEL   = listOf("A1", "A7", "A8", "A9a", "A9b", "D1", "D2")
    private val A_INFLOW  = listOf("A2", "A4", "A10", "A11", "A11a", "A12", "D3", "D4")
    private val A_CHAIN   = listOf("A3", "A5", "A6", "D5", "D6", "D7")
    private val B_LEVEL   = listOf("B4", "B5", "B6")
    private val B_SHAPE   = listOf("B1", "B3", "B3p")
    private val B_MEMORY  = listOf("B7")
    private val C_QUALITY = listOf("C1", "C2", "C2p")
    private val C_BREADTH = listOf("C3")
    private val C_FEEDBACK = listOf("C4", "C6")
    private val C_DECAY   = listOf("C7")
    private val E_TEMP    = listOf("E1", "E2")

    private val ALL_FACTORS = (A_LEVEL + A_INFLOW + A_CHAIN +
        B_LEVEL + B_SHAPE + B_MEMORY +
        C_QUALITY + C_BREADTH + C_FEEDBACK + C_DECAY + E_TEMP).distinct()

    // ---- 公共入口 ----

    /**
     * 对 [records]（按 tradeDate 升序）计算逐日 score。
     * 返回的 DoubleArray 与 records 等长，前 (W-1) 项为 NaN（窗口不足）。
     */
    fun scoreSeries(records: List<SentimentFactorRecord>): DoubleArray {
        val n = records.size
        val scores = DoubleArray(n) { Double.NaN }

        // 0. 窗口 W —— 优先固定窗口，否则自适应
        val yComposite = DoubleArray(n) { records[it].yComposite ?: 0.0 }
        val W = if (fixedWindow > 0) fixedWindow else adaptiveWindow(yComposite)
        if (W < minWindow || n < W + 1) return scores

        // 1. 提取因子数组
        val factorArrays = mutableMapOf<String, DoubleArray>()
        for (fk in ALL_FACTORS) {
            factorArrays[fk] = DoubleArray(n) { records[it].factors[fk] ?: Double.NaN }
        }

        // 2. 逐因子计算贡献能量 e_k
        val eSeries = mutableMapOf<String, DoubleArray>()
        val alpha = entropyAlpha(yComposite, W)
        for (fk in ALL_FACTORS) {
            eSeries[fk] = factorEnergy(factorArrays[fk]!!, yComposite, W, alpha)
        }

        // 3. 逐日：聚合分组势能 → 频带分解 → 温度调节 → 系统能量
        val eSys = DoubleArray(n) { Double.NaN }
        for (t in (W - 1) until n) {
            eSys[t] = systemEnergy(eSeries, factorArrays, yComposite, t, W)
        }

        // 4. 能量累加
        val sSys = accumulateEnergy(eSys, W)

        // 5. ECDF 概率连接
        for (t in (W - 1) until n) {
            scores[t] = ecdf(sSys, t, W)
        }

        return scores
    }

    /** 公开入口：仅用给定序列的自相关估算窗口长度 */
    fun adaptiveWindowFor(y: DoubleArray, maxLag: Int = 120): Int = adaptiveWindow(y, maxLag)

    // ---- 阶段 0: 自适应窗口（严格仅用过去数据: 从倒数 maxLag→现在 算自相关） ----
    private fun adaptiveWindow(y: DoubleArray, maxLag: Int = 120): Int {
        // 只取序列最后的窗口算自相关，避免全序列前视
        val lookback = min(y.size, maxLag * 3)
        val tail = y.copyOfRange(max(0, y.size - lookback), y.size)
        val valid = tail.filter { it.isFinite() }.toDoubleArray()
        if (valid.size < 60) return minWindow
        val n = valid.size
        for (lag in 1..min(maxLag, n / 2)) {
            val corr = pearson(
                valid.copyOfRange(0, n - lag),
                valid.copyOfRange(lag, n)
            )
            if (corr != null && corr < 1.0 / kotlin.math.E) {
                val w = max(minWindow, min(maxWindow, lag))
                return w
            }
        }
        return maxWindow
    }

    // ---- 阶段 2: 单因子贡献能量 e_k ----
    private fun factorEnergy(f: DoubleArray, y: DoubleArray, W: Int, alpha: Double): DoubleArray {
        val n = f.size
        val e = DoubleArray(n) { Double.NaN }

        // Determine valid range (exclude NaN)
        for (t in (W - 1) until n) {
            val windowF = sliceValid(f, t - W + 1, t)
            val windowY = sliceValid(y, t - W + 1, t)
            if (windowF.size < W / 2) continue

            // μ_k(T) = (f(T) - median) / (1.4826 * MAD)
            val median = median(windowF)
            val madVal = mad(windowF, median)
            if (madVal < 1e-10) { e[t] = 0.0; continue }
            val mu = (f[t] - median) / (1.4826 * madVal)

            // ψ_k(T) = sign(corr(Δf, ΔY)) * |corr|^α
            val df = diff(windowF)
            val dy = diff(windowY)
            val corr = pearson(df, dy)
            if (corr == null) { e[t] = 0.0; continue }
            val power = Math.pow(abs(corr), (alpha + theta.thetaAlpha).coerceAtLeast(0.01))
            val psi = sign(corr) * if (power.isFinite()) power else abs(corr)

            e[t] = psi * mu
        }
        return e
    }

    // ---- 阶段 3: 分组势能聚合 ----
    private fun groupEnergy(
        eSeries: Map<String, DoubleArray>,
        factorArrays: Map<String, DoubleArray>,
        y: DoubleArray,
        t: Int,
        W: Int,
    ): Double {
        val eA = treeEnergy(eSeries, A_LEVEL, A_INFLOW, A_CHAIN, factorArrays, y, t, W)
        val eB = treeEnergy(eSeries, B_LEVEL, B_SHAPE, B_MEMORY, factorArrays, y, t, W)
        val eC = treeEnergy(eSeries, C_QUALITY, C_BREADTH,
            C_FEEDBACK + C_DECAY, factorArrays, y, t, W)
        return eA + eB + eC
    }

    private data class LayerResult(val energy: Double, val prev: Double)

    private fun treeEnergy(
        eSeries: Map<String, DoubleArray>,
        levelFactors: List<String>,
        inflowFactors: List<String>,
        chainFactors: List<String>,
        factorArrays: Map<String, DoubleArray>,
        y: DoubleArray,
        t: Int,
        W: Int,
    ): Double {
        val levelE = layerEnergy(eSeries, levelFactors, t, W)
        val inflowE = layerEnergy(eSeries, inflowFactors, t, W)
        val chainE = layerEnergy(eSeries, chainFactors, t, W)

        // ∇ = level(T) - level(T-1)
        val levelPrev = if (t > 0) layerEnergy(eSeries, levelFactors, t - 1, W) else 0.0
        val grad = levelE - levelPrev

        // ξ = correlation over recent W between chain and ∇level
        val xi = chainCouplingStrength(eSeries, chainFactors, levelFactors, t, W)

        return levelE + grad + theta.thetaXi * xi * chainE
    }

    private fun layerEnergy(
        eSeries: Map<String, DoubleArray>,
        factors: List<String>,
        t: Int,
        W: Int,
    ): Double {
        val valid = factors.filter { fk ->
            val e = eSeries[fk]
            e != null && t < e.size && e[t].isFinite()
        }
        if (valid.isEmpty()) return 0.0

        val energies = valid.map { eSeries[it]!![t] }
        // Boltzmann entropy weights λ_k = softmax(-H_k)
        val entropies = valid.map { fk ->
            entropyOf(eSeries[fk]!!, t, W)
        }
        val maxNegH = entropies.map { -it }.max()
        val weights = entropies.map { h ->
            exp((-h - maxNegH) * theta.thetaLambda)
        }
        val sumW = weights.sum()
        if (sumW < 1e-10) return energies.average()

        return energies.indices.sumOf { i ->
            (weights[i] / sumW) * energies[i]
        }
    }

    private fun chainCouplingStrength(
        eSeries: Map<String, DoubleArray>,
        chainFactors: List<String>,
        levelFactors: List<String>,
        t: Int,
        W: Int,
    ): Double {
        if (t < W) return 0.0
        val chainSeries = DoubleArray(W) { idx ->
            val tt = t - W + 1 + idx
            layerEnergy(eSeries, chainFactors, tt, W)
        }
        val levelSeries = DoubleArray(W) { idx ->
            val tt = t - W + 1 + idx
            layerEnergy(eSeries, levelFactors, tt, W)
        }
        val gradLevel = diff(levelSeries)
        val gradChain = diff(chainSeries)
        return pearson(gradChain, gradLevel) ?: 0.0
    }

    // ---- 阶段 3-5: 系统总能量 ----
    private fun systemEnergy(
        eSeries: Map<String, DoubleArray>,
        factorArrays: Map<String, DoubleArray>,
        y: DoubleArray,
        t: Int,
        W: Int,
    ): Double {
        val gA = treeEnergy(eSeries, A_LEVEL, A_INFLOW, A_CHAIN, factorArrays, y, t, W)
        val gB = treeEnergy(eSeries, B_LEVEL, B_SHAPE, B_MEMORY, factorArrays, y, t, W)
        val gC = treeEnergy(eSeries, C_QUALITY, C_BREADTH, C_FEEDBACK + C_DECAY, factorArrays, y, t, W)
        val gE = layerEnergy(eSeries, E_TEMP, t, W)

        // Temperature: T_temp = 1 / (1 + |E_E|^β)
        val cvE = if (t >= W) cvOf(eSeries, E_TEMP, t, W) else 0.0
        val beta = theta.thetaBeta * (1.0 + cvE)
        val tTemp = 1.0 / (1.0 + Math.pow(abs(gE), beta.coerceAtLeast(0.1)))

        // Softmax-weighted sum of three directional trees (v2: D tree merged into A)
        val logits = doubleArrayOf(theta.thetaWA, theta.thetaWB, theta.thetaWC)
        val maxLogit = logits.max()
        val weights = logits.map { exp(it - maxLogit) }
        val sumW = weights.sum()

        val dirSum = if (sumW > 1e-10) {
            (weights[0] / sumW) * gA + (weights[1] / sumW) * gB +
                (weights[2] / sumW) * gC
        } else {
            (gA + gB + gC) / 3.0
        }

        return tTemp * dirSum
    }

    // ---- 阶段 6: 能量累加 ----
    private fun accumulateEnergy(eSys: DoubleArray, W: Int): DoubleArray {
        val n = eSys.size
        val sSys = DoubleArray(n) { Double.NaN }
        val start = eSys.indexOfFirst { it.isFinite() }
        if (start < 0) return sSys
        val deSys = diff(eSys)
        // Compute half-life from entropy of ΔE
        for (t in (start + W) until n) {
            val validFrom = max(start + 1, t - W + 1)
            val deWindow = deSys.copyOfRange(validFrom, min(t + 1, deSys.size))
                .filter { it.isFinite() }.toDoubleArray()
            if (deWindow.size < 10) continue
            val hDE = entropy(deWindow)
            val tauHalf = W * hDE / max(ln(W.toDouble()), 1e-6) * exp(theta.thetaTau)
            val tau = tauHalf / ln(2.0) // convert half-life to time constant
            var weightSum = 0.0
            var weightedSum = 0.0
            for (tau0 in validFrom..t) {
                val de = deSys.getOrNull(tau0) ?: continue
                if (!de.isFinite()) continue
                val w = exp(-(t - tau0).toDouble() / tau.coerceAtLeast(1.0))
                weightedSum += w * de
                weightSum += w
            }
            sSys[t] = if (weightSum > 1e-10) weightedSum / weightSum else 0.0
        }
        return sSys
    }

    // ---- 阶段 7: ECDF 概率连接 ----
    private fun ecdf(sSys: DoubleArray, t: Int, W: Int): Double {
        val from = max(0, t - W + 1)
        val current = sSys[t]
        if (!current.isFinite()) return 0.5
        var count = 0
        var total = 0
        for (i in from until t) {
            if (sSys[i].isFinite()) {
                total++
                if (sSys[i] <= current) count++
            }
        }
        return if (total > 0) count.toDouble() / total.toDouble() else 0.5
    }

    // ---- 统计工具函数 ----

    private fun sliceValid(arr: DoubleArray, from: Int, to: Int): DoubleArray {
        val result = mutableListOf<Double>()
        for (i in max(0, from)..min(to, arr.lastIndex)) {
            if (arr[i].isFinite()) result.add(arr[i])
        }
        return result.toDoubleArray()
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun mad(values: DoubleArray, median: Double): Double {
        val deviations = values.filter { it.isFinite() }.map { abs(it - median) }.sorted()
        if (deviations.isEmpty()) return 0.0
        return median(deviations.toDoubleArray())
    }

    private fun diff(values: DoubleArray): DoubleArray {
        val n = values.size
        val result = DoubleArray(max(0, n - 1)) { Double.NaN }
        for (i in 1 until n) {
            if (values[i].isFinite() && values[i - 1].isFinite()) {
                result[i - 1] = values[i] - values[i - 1]
            }
        }
        return result
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        if (x.size != y.size || x.size < 3) return null
        val pairs = x.indices.mapNotNull { i ->
            if (x[i].isFinite() && y[i].isFinite()) x[i] to y[i] else null
        }
        if (pairs.size < 3) return null
        val mx = pairs.map { it.first }.average()
        val my = pairs.map { it.second }.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for ((xi, yi) in pairs) {
            val vx = xi - mx
            val vy = yi - my
            num += vx * vy
            dx += vx * vx
            dy += vy * vy
        }
        val den = sqrt(dx * dy)
        return if (den < 1e-12) null else num / den
    }

    private fun entropy(values: DoubleArray): Double {
        val valid = values.filter { it.isFinite() }
        if (valid.size < 5) return 0.0
        val sorted = valid.sorted()
        val nBins = min(20, valid.size / 3).coerceAtLeast(2)
        val minV = sorted.first()
        val maxV = sorted.last()
        if (maxV - minV < 1e-12) return 0.0
        val binWidth = (maxV - minV) / nBins
        val counts = IntArray(nBins)
        for (v in valid) {
            val bin = min(((v - minV) / binWidth).toInt(), nBins - 1)
            counts[bin]++
        }
        var h = 0.0
        val total = valid.size.toDouble()
        for (c in counts) {
            if (c > 0) {
                val p = c / total
                h -= p * ln(p)
            }
        }
        return h
    }

    private fun entropyOf(series: DoubleArray, t: Int, W: Int): Double {
        val window = sliceValid(series, t - W + 1, t)
        return entropy(window)
    }

    private fun entropyAlpha(y: DoubleArray, W: Int): Double {
        val window = sliceValid(y, max(0, y.size - W), y.lastIndex)
        val h = entropy(window)
        return h / max(ln(W.toDouble()), 1e-6)
    }

    private fun cvOf(eSeries: Map<String, DoubleArray>, factors: List<String>, t: Int, W: Int): Double {
        val values = mutableListOf<Double>()
        for (fk in factors) {
            val s = eSeries[fk] ?: continue
            for (i in max(0, t - W + 1)..t) {
                if (i < s.size && s[i].isFinite()) values.add(s[i])
            }
        }
        if (values.size < 5) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        return if (mean > 1e-10) std / mean else 0.0
    }

}

/** 与 SentimentFactorDailyRecord 解耦的轻量输入类型 */
data class SentimentFactorRecord(
    val tradeDate: String,               // "2020-01-02"
    val factors: Map<String, Double?>,   // 38 factor key → value
    val y1Raw: Double?,
    val y2Raw: Double?,
    val y3Raw: Double?,
    val yComposite: Double?,
)

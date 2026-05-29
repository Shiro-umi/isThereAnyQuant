package org.shiroumi.strategy.research.topic.factor.study

import org.shiroumi.strategy.research.topic.factor.signal.Fft
import org.shiroumi.strategy.research.topic.factor.signal.power
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 量价因子族（VV/VP）的**序列构造器** —— 把两条市场级基础量序列推导成各因子序列。
 *
 * 设计依据：`private/research-docs/volume-price-factor-formula.html` §3/§4。
 * 架构语义（先聚合后推导）：基础序列 `vpmRet`（市场等权对数收益 r）、`vpmTurn`（市场等权换手率 τ）
 * 已由 [org.shiroumi.database.sentiment.VolumePriceMarketCalculator] 每日等权聚合好；
 * 这里只在这两条**市场级序列**上做「前序序列推导」，得到各因子序列，交给 ResonanceCore 算共振。
 *
 * 所有因子都是**因果**的：第 t 个值只用到 `..t`（或 `..t-1`）的前序数据，无未来泄漏。
 * 前 `window` 个点信息不足处置 null（下游对齐时自然丢弃）。
 *
 * 注：VPspec/VPstate/VPres 三个「推导算子」不在此列为独立输入序列——它们是 ResonanceCore.buildMetric
 * 对因子序列的天然产物（相干/相位、状态切片、残差 IC 已含在 ResonanceMetric 的度量维度里），
 * 重复列为输入会概念冗余。本构造器产出 13 条可独立成序列的因子。
 */
object VolumePriceFactors {

    /** 因子名 → 该因子的市场级序列（与输入等长，不足处 null）。 */
    fun buildAll(ret: List<Double?>, turn: List<Double?>): LinkedHashMap<String, List<Double?>> {
        val r = ret.map { it }                 // 价：对数收益序列
        val tau = turn.map { it }              // 量：换手率序列
        val v = volumeAnomaly(tau, baseWindow = 20)   // 量异动 v_t = ln(τ_t / 昨基准)
        val out = LinkedHashMap<String, List<Double?>>()

        // ── 线 B · 纯量序列因子（VV）──
        out["VV1"] = rollingSlope(tau, window = 10)                       // 量能趋势斜率
        out["VV2"] = memoryRatio(tau, window = 60)                       // 量能记忆衰减比 ρ1/(1+|ρ5|)
        out["VV3"] = runLength(v)                                        // 放量/缩量游程（带符号）
        out["VV4"] = rollingCv(tau, window = 20)                         // 量能变异系数
        out["VV5"] = pulseZ(tau, window = 20)                           // 量能脉冲 z 分（用昨日前序窗）
        out["VV6"] = nearFarRatio(tau, near = 3, far = 20)              // 近远期能量比
        out["VV7"] = spectralEntropy(tau, window = 32)                  // 量能节律谱熵
        out["VV8"] = leadShareV(tau, r, window = 40, maxLead = 3)       // 量能领先切换占比

        // ── 线 A · 量价交互因子（VP）──
        out["VP0"] = pairwise(r, v) { ri, vi -> sign(ri) * vi }                 // 量价配合度
        out["VP1"] = velocityDiff(v, r)                                         // 量速价速差（量在价先）
        out["VP2v"] = secondDiff(v)                                            // 量加速度
        out["VP2c"] = corrCurvature(r, v, window = 20)                        // 量价协同变化率
        out["VPbeta"] = rollingBeta(r, v, window = 20)                        // 量价弹性 β
        return out
    }

    // ── 量异动：v_t = ln(τ_t / 截至昨日的 baseWindow 日均换手) ──
    private fun volumeAnomaly(tau: List<Double?>, baseWindow: Int): List<Double?> =
        tau.indices.map { t ->
            val cur = tau[t] ?: return@map null
            if (cur <= 0.0 || t < baseWindow) return@map null
            val base = window(tau, t - baseWindow, t)   // [t-baseWindow, t-1]
            val mean = base.average()
            if (mean <= 0.0) null else ln(cur / mean)
        }

    private fun rollingSlope(s: List<Double?>, window: Int): List<Double?> =
        s.indices.map { t ->
            if (t < window - 1) return@map null
            val ys = window(s, t - window + 1, t + 1)
            if (ys.size < window) return@map null
            // 关于 k=0..window-1 的最小二乘斜率
            val n = ys.size
            val meanK = (n - 1) / 2.0
            val meanY = ys.average()
            var num = 0.0
            var den = 0.0
            for (k in 0 until n) {
                num += (k - meanK) * (ys[k] - meanY)
                den += (k - meanK) * (k - meanK)
            }
            if (den == 0.0) null else num / den
        }

    private fun memoryRatio(s: List<Double?>, window: Int): List<Double?> =
        s.indices.map { t ->
            if (t < window - 1) return@map null
            val seg = window(s, t - window + 1, t + 1)
            if (seg.size < window) return@map null
            val rho1 = autocorr(seg, 1) ?: return@map null
            val rho5 = autocorr(seg, 5) ?: return@map null
            rho1 / (1.0 + abs(rho5))
        }

    private fun runLength(v: List<Double?>): List<Double?> =
        v.indices.map { t ->
            val cur = v[t] ?: return@map null
            val s = sign(cur)
            if (s == 0.0) return@map 0.0
            var n = 1
            var i = t - 1
            while (i >= 0 && v[i] != null && sign(v[i]!!) == s) { n++; i-- }
            s * n
        }

    private fun rollingCv(s: List<Double?>, window: Int): List<Double?> =
        s.indices.map { t ->
            if (t < window - 1) return@map null
            val seg = window(s, t - window + 1, t + 1)
            if (seg.size < window) return@map null
            val mean = seg.average()
            if (mean <= 0.0) return@map null
            val std = sqrt(seg.sumOf { (it - mean) * (it - mean) } / seg.size)
            std / mean
        }

    private fun pulseZ(s: List<Double?>, window: Int): List<Double?> =
        s.indices.map { t ->
            val cur = s[t] ?: return@map null
            if (t < window) return@map null
            val base = window(s, t - window, t)   // 截至昨日，不含当日
            if (base.size < window) return@map null
            val mean = base.average()
            val std = sqrt(base.sumOf { (it - mean) * (it - mean) } / base.size)
            if (std == 0.0) null else (cur - mean) / std
        }

    private fun nearFarRatio(s: List<Double?>, near: Int, far: Int): List<Double?> =
        s.indices.map { t ->
            if (t < far - 1) return@map null
            val nearSeg = window(s, t - near + 1, t + 1)
            val farSeg = window(s, t - far + 1, t + 1)
            if (nearSeg.size < near || farSeg.size < far) return@map null
            val nm = nearSeg.average()
            val fm = farSeg.average()
            if (nm <= 0.0 || fm <= 0.0) null else ln(nm / fm)
        }

    private fun spectralEntropy(s: List<Double?>, window: Int): List<Double?> =
        s.indices.map { t ->
            if (t < window - 1) return@map null
            val seg = window(s, t - window + 1, t + 1)
            if (seg.size < window) return@map null
            val mean = seg.average()
            val detr = DoubleArray(seg.size) { seg[it] - mean }
            val spectrum = Fft.fftPadded(detr)
            // 单边功率谱：取 [1, N/2]（去直流 bin 0），用作节律能量分布
            val half = spectrum.size / 2
            val power = DoubleArray(max(0, half - 1)) { spectrum[it + 1].power() }
            val total = power.sum()
            if (total <= 0.0) return@map null
            var ent = 0.0
            for (p in power) {
                val pi = p / total
                if (pi > 0.0) ent -= pi * ln(pi)
            }
            ent
        }

    // 量能拐点领先价格拐点的占比（窗内）
    private fun leadShareV(tau: List<Double?>, r: List<Double?>, window: Int, maxLead: Int): List<Double?> =
        tau.indices.map { t ->
            if (t < window - 1) return@map null
            val tSeg = window(tau, t - window + 1, t + 1)
            val rSeg = window(r, t - window + 1, t + 1)
            if (tSeg.size < window || rSeg.size < window) return@map null
            val volTurns = turningPoints(tSeg)
            val priceTurns = turningPoints(rSeg)
            if (priceTurns.isEmpty()) return@map null
            val led = priceTurns.count { pt -> volTurns.any { vt -> vt < pt && pt - vt <= maxLead } }
            led.toDouble() / priceTurns.size
        }

    private fun velocityDiff(v: List<Double?>, r: List<Double?>): List<Double?> =
        v.indices.map { t ->
            if (t < 1) return@map null
            val vDot = nullableDiff(v, t) ?: return@map null
            val rDot = nullableDiff(r, t) ?: return@map null
            vDot - rDot
        }

    private fun secondDiff(s: List<Double?>): List<Double?> =
        s.indices.map { t ->
            if (t < 2) return@map null
            val d1 = nullableDiff(s, t) ?: return@map null
            val d0 = nullableDiff(s, t - 1) ?: return@map null
            d1 - d0
        }

    private fun corrCurvature(r: List<Double?>, v: List<Double?>, window: Int): List<Double?> {
        val corr = r.indices.map { t ->
            if (t < window - 1) return@map null
            val rs = window(r, t - window + 1, t + 1)
            val vs = window(v, t - window + 1, t + 1)
            if (rs.size < window || vs.size < window) return@map null
            pearson(rs.toDoubleArray(), vs.toDoubleArray())
        }
        return corr.indices.map { t -> if (t < 1) null else nullableDiff(corr, t) }
    }

    private fun rollingBeta(r: List<Double?>, v: List<Double?>, window: Int): List<Double?> =
        r.indices.map { t ->
            if (t < window - 1) return@map null
            val rs = window(r, t - window + 1, t + 1)
            val vs = window(v, t - window + 1, t + 1)
            if (rs.size < window || vs.size < window) return@map null
            // r 对 v 回归的斜率：cov(v,r)/var(v)
            val mv = vs.average()
            val mr = rs.average()
            var num = 0.0
            var den = 0.0
            for (i in vs.indices) {
                num += (vs[i] - mv) * (rs[i] - mr)
                den += (vs[i] - mv) * (vs[i] - mv)
            }
            if (den == 0.0) null else num / den
        }

    private fun pairwise(a: List<Double?>, b: List<Double?>, op: (Double, Double) -> Double): List<Double?> =
        a.indices.map { t ->
            val x = a[t]
            val y = b[t]
            if (x == null || y == null) null else op(x, y)
        }

    // ── 工具 ──

    /** 取 [from, to) 区间内非 null 值（用于滚动窗口）。 */
    private fun window(s: List<Double?>, from: Int, to: Int): List<Double> {
        val out = ArrayList<Double>(to - from)
        for (i in max(0, from) until to) s[i]?.let(out::add)
        return out
    }

    private fun nullableDiff(s: List<Double?>, t: Int): Double? {
        val cur = s[t] ?: return null
        val prev = s[t - 1] ?: return null
        return cur - prev
    }

    private fun autocorr(seg: List<Double>, lag: Int): Double? {
        if (seg.size <= lag + 2) return null
        val mean = seg.average()
        var num = 0.0
        var den = 0.0
        for (i in seg.indices) den += (seg[i] - mean) * (seg[i] - mean)
        for (i in lag until seg.size) num += (seg[i] - mean) * (seg[i - lag] - mean)
        return if (den == 0.0) null else num / den
    }

    private fun turningPoints(seg: List<Double>): List<Int> {
        val out = ArrayList<Int>()
        for (i in 1 until seg.size - 1) {
            if ((seg[i] > seg[i - 1] && seg[i] > seg[i + 1]) ||
                (seg[i] < seg[i - 1] && seg[i] < seg[i + 1])
            ) out.add(i)
        }
        return out
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in x.indices) {
            num += (x[i] - mx) * (y[i] - my)
            dx += (x[i] - mx) * (x[i] - mx)
            dy += (y[i] - my) * (y[i] - my)
        }
        val den = sqrt(dx * dy)
        return if (den == 0.0) null else num / den
    }

    private fun sign(v: Double): Double = when {
        v > 0.0 -> 1.0
        v < 0.0 -> -1.0
        else -> 0.0
    }
}

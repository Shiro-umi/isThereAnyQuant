package org.shiroumi.strategy.research.topic.reversal

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 市场级技术振荡器 —— 把均值回归研究报告的核心技术指标（RSI/布林%B/Z-Score/ADX）市场级化。
 *
 * 动机（2026-06-01，参考用户提供的「T+1双向反攻」研究报告）：报告反复强调 RSI(2)/布林%B/Z-Score 是
 * 验证最充分的短期反转触发器，ADX<25 是均值回归的「生存门控」（强趋势市必失效）。这些**振荡器口径**
 * 在现有因子集（量价 VP / 价形态 M / trend 亢奋度 / 横截面矩）里**没有等价物**——是真正未测过的正交维度。
 *
 * 全部从市场级**等权日收益** retClose 累乘出的合成价格指数上计算（报告口径=收盘价序列）。纯函数、
 * 无状态、严格用截至 t 的历史（rolling），下游再走 d1/d2 + 交互管线。是否带 OOS 判别力由 walk-forward 验证。
 */
internal object PivotOscillators {

    /** 一日的振荡器快照（市场级）。null 表示窗口不足。 */
    data class Snapshot(
        val rsi2: Double?,      // 2 周期 RSI（报告：短期反转最强触发器）
        val rsi9: Double?,      // 9 周期 RSI（A股适应参数）
        val pctB: Double?,      // 布林 %B（价格在 N 日均值 ±kσ 通道内的相对位置）
        val zScore: Double?,    // 20 日 Z-Score（价格偏离均值的标准差数）
        val adx: Double?,       // 14 日 ADX（趋势强度；报告：<25 才适合均值回归）
        val hurst: Double?,     // 60 日 Hurst 指数（<0.5 均值回归态/反持续，>0.5 趋势态/持续；报告：状态确认）
    )

    /**
     * 从市场级等权日收益序列计算逐日振荡器。
     * @param retClose 市场级等权日收益（对数或简单收益均可，仅用于累乘出价格水平的相对路径）。
     */
    fun compute(retClose: List<Double?>): List<Snapshot> {
        val n = retClose.size
        // 累乘出合成价格指数（基准 1.0；缺失日按 0 收益处理，保持序列连续）。
        val price = DoubleArray(n)
        var p = 1.0
        for (i in 0 until n) {
            val r = retClose[i]?.takeIf { it.isFinite() } ?: 0.0
            p *= (1.0 + r)
            price[i] = p
        }
        val rsi2 = rsiSeries(price, 2)
        val rsi9 = rsiSeries(price, 9)
        val pctB = pctBSeries(price, 20, 2.0)
        val zScore = zScoreSeries(price, 20)
        val adx = adxSeries(price, 14)
        // Hurst 在收益序列上算（长程记忆是收益的性质，非价格水平）；对数收益更稳。
        val logRet = DoubleArray(n)
        for (i in 1 until n) logRet[i] = ln(price[i] / price[i - 1])
        val hurst = hurstSeries(logRet, 60)
        return (0 until n).map { Snapshot(rsi2[it], rsi9[it], pctB[it], zScore[it], adx[it], hurst[it]) }
    }

    /**
     * 滚动 Hurst 指数（R/S 重标极差法，window 日窗）。
     * H<0.5=反持续（均值回归态，价格偏离后倾向回归）、H≈0.5=随机游走、H>0.5=持续（趋势态）。
     * 报告把 Hurst<0.5 列为「确认均值回归状态」的过滤器——比 ADX 更本质（测长程记忆而非趋势强度）。
     * 单窗 R/S：去均值累积离差的极差 R 除以标准差 S；H = ln(R/S)/ln(window)（单尺度近似，够做日频状态判别）。
     */
    private fun hurstSeries(ret: DoubleArray, window: Int): Array<Double?> {
        val n = ret.size
        val out = arrayOfNulls<Double>(n)
        if (n <= window) return out
        for (i in window until n) {
            var mean = 0.0
            for (j in i - window + 1..i) mean += ret[j]
            mean /= window
            var cum = 0.0; var minC = 0.0; var maxC = 0.0; var sumSq = 0.0
            for (j in i - window + 1..i) {
                val dev = ret[j] - mean
                cum += dev
                if (cum < minC) minC = cum
                if (cum > maxC) maxC = cum
                sumSq += dev * dev
            }
            val rng = maxC - minC
            val sd = sqrt(sumSq / window)
            out[i] = if (sd <= 0.0 || rng <= 0.0) 0.5 else ln(rng / sd) / ln(window.toDouble())
        }
        return out
    }

    /** Wilder 平滑 RSI（period 周期）；前 period 根不足返回 null。 */
    private fun rsiSeries(price: DoubleArray, period: Int): Array<Double?> {
        val n = price.size
        val out = arrayOfNulls<Double>(n)
        if (n <= period) return out
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val d = price[i] - price[i - 1]
            if (d > 0) avgGain += d else avgLoss += -d
        }
        avgGain /= period; avgLoss /= period
        out[period] = rsiOf(avgGain, avgLoss)
        for (i in period + 1 until n) {
            val d = price[i] - price[i - 1]
            val gain = if (d > 0) d else 0.0
            val loss = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiOf(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiOf(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    /** 布林 %B = (P − 下轨)/(上轨 − 下轨)，下/上轨 = N日均值 ∓ kσ。<0 极端超卖、>1 极端超买。 */
    private fun pctBSeries(price: DoubleArray, win: Int, k: Double): Array<Double?> {
        val n = price.size
        val out = arrayOfNulls<Double>(n)
        for (i in win - 1 until n) {
            var sum = 0.0
            for (j in i - win + 1..i) sum += price[j]
            val mean = sum / win
            var v = 0.0
            for (j in i - win + 1..i) v += (price[j] - mean) * (price[j] - mean)
            val sd = sqrt(v / win)
            if (sd <= 0.0) { out[i] = 0.5; continue }
            val lower = mean - k * sd; val upper = mean + k * sd
            out[i] = (price[i] - lower) / (upper - lower)
        }
        return out
    }

    /** Z-Score = (P − N日均值)/N日标准差。报告核心：|Z|>2 极端偏离。 */
    private fun zScoreSeries(price: DoubleArray, win: Int): Array<Double?> {
        val n = price.size
        val out = arrayOfNulls<Double>(n)
        for (i in win - 1 until n) {
            var sum = 0.0
            for (j in i - win + 1..i) sum += price[j]
            val mean = sum / win
            var v = 0.0
            for (j in i - win + 1..i) v += (price[j] - mean) * (price[j] - mean)
            val sd = sqrt(v / win)
            out[i] = if (sd <= 0.0) 0.0 else (price[i] - mean) / sd
        }
        return out
    }

    /**
     * ADX（趋势强度，仅用收盘价路径近似）。报告：ADX<25=震荡市=均值回归主场。
     * 标准 ADX 需 high/low；这里用收盘价的正/负变动作 +DM/−DM、|ΔP| 作 TR 的近似（市场级合成指数无真实 HL）。
     */
    private fun adxSeries(price: DoubleArray, period: Int): Array<Double?> {
        val n = price.size
        val out = arrayOfNulls<Double>(n)
        if (n <= 2 * period) return out
        val plusDm = DoubleArray(n); val minusDm = DoubleArray(n); val tr = DoubleArray(n)
        for (i in 1 until n) {
            val d = price[i] - price[i - 1]
            plusDm[i] = if (d > 0) d else 0.0
            minusDm[i] = if (d < 0) -d else 0.0
            tr[i] = abs(d)
        }
        // Wilder 平滑 +DI / −DI / DX，再平滑成 ADX。
        var sTr = 0.0; var sPlus = 0.0; var sMinus = 0.0
        for (i in 1..period) { sTr += tr[i]; sPlus += plusDm[i]; sMinus += minusDm[i] }
        val dx = DoubleArray(n)
        for (i in period + 1 until n) {
            sTr = sTr - sTr / period + tr[i]
            sPlus = sPlus - sPlus / period + plusDm[i]
            sMinus = sMinus - sMinus / period + minusDm[i]
            if (sTr <= 0.0) { dx[i] = 0.0; continue }
            val plusDi = 100.0 * sPlus / sTr
            val minusDi = 100.0 * sMinus / sTr
            val sum = plusDi + minusDi
            dx[i] = if (sum <= 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / sum
        }
        // ADX = DX 的 Wilder 平滑（从首个可用 DX 段起）。
        val firstDx = period + 1
        if (firstDx + period >= n) return out
        var adx = 0.0
        for (i in firstDx until firstDx + period) adx += dx[i]
        adx /= period
        out[firstDx + period - 1] = adx
        for (i in firstDx + period until n) {
            adx = (adx * (period - 1) + dx[i]) / period
            out[i] = adx
        }
        return out
    }
}

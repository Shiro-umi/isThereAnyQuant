package org.shiroumi.strategy.breakdown

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 回归通道几何基座（逐行移植 temp/pa_panel.py 的 linreg_channel / atr）。
 *
 * 因果铁律：所有几何工具只吃回看窗 close/high/low，不触碰 endIdx 之后。
 * 全程 Double（生产侧 close_qfq.toDouble()），不使用 numpy 之外的任何高阶库。
 */

/**
 * 线性回归通道（移植 pa_panel.linreg_channel）。
 *
 * 对 [endIdx-lookback+1 .. endIdx] 的 series 做最小二乘线性回归。
 * slope/intercept 以窗内相对位置 x=0..lookback-1 为自变量。
 * residStd = sqrt(mean(resid^2))，总体口径除以 n（非样本 std）。
 * 数据不足 / 含非有限值 → slope/intercept/residStd 全 NaN。
 */
data class LinregChannel(val slope: Double, val intercept: Double, val residStd: Double)

private val NAN_CHANNEL = LinregChannel(Double.NaN, Double.NaN, Double.NaN)

/**
 * 移植 pa_panel.linreg_channel(close, end_idx, lookback)。
 * @param series 单只票的连续序列（升序）。
 * @param endIdx 回看窗右边界（含），因果可见边界。
 * @param lookback 回看根数。
 */
fun linregChannel(series: DoubleArray, endIdx: Int, lookback: Int): LinregChannel {
    val s = endIdx - lookback + 1
    if (s < 0) return NAN_CHANNEL
    // seg = close[s:end_idx+1]
    val n = endIdx + 1 - s
    if (n < lookback) return NAN_CHANNEL
    // np.all(np.isfinite(seg)) —— close 为正的有限值
    for (i in s..endIdx) {
        if (!series[i].isFinite()) return NAN_CHANNEL
    }
    // x = arange(lookback); y = seg
    val xm = (lookback - 1) / 2.0   // mean of 0..lookback-1
    var ym = 0.0
    for (i in s..endIdx) ym += series[i]
    ym /= lookback
    // sxx = sum((x-xm)^2); sxy = sum((x-xm)*(y-ym))
    var sxx = 0.0
    var sxy = 0.0
    for (k in 0 until lookback) {
        val dx = k - xm
        val dy = series[s + k] - ym
        sxx += dx * dx
        sxy += dx * dy
    }
    if (sxx <= 0.0) return NAN_CHANNEL
    val slope = sxy / sxx
    val intercept = ym - slope * xm
    // resid_std = sqrt(mean(resid^2)) —— 除以 n（lookback），总体口径
    var sse = 0.0
    for (k in 0 until lookback) {
        val fit = intercept + slope * k
        val resid = series[s + k] - fit
        sse += resid * resid
    }
    val residStd = sqrt(sse / lookback)
    return LinregChannel(slope, intercept, residStd)
}

/**
 * 移植 pa_panel.atr(high, low, close, end_idx, period=14)。
 *
 * Wilder True Range 的简单均值 ATR(period)，截至 endIdx（含）。
 * 切片 [endIdx-period+1 .. endIdx] 的 H/L/C，前一日 close 用 [endIdx-period .. endIdx-1]。
 * 数据不足（endIdx-period < 1）或含非有限值返回 NaN。
 */
fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, endIdx: Int, period: Int = 14): Double {
    if (endIdx - period < 1) return Double.NaN
    // hi/lo/cl = [end-period+1 .. end]; cp = [end-period .. end-1]（前一日 close）
    var sum = 0.0
    for (i in (endIdx - period + 1)..endIdx) {
        val hi = high[i]
        val lo = low[i]
        val cl = close[i]
        val cp = close[i - 1]
        if (!hi.isFinite() || !lo.isFinite() || !cl.isFinite() || !cp.isFinite()) return Double.NaN
        // tr = max(hi-lo, |hi-cp|, |lo-cp|)
        val tr = maxOf(hi - lo, abs(hi - cp), abs(lo - cp))
        sum += tr
    }
    return sum / period
}

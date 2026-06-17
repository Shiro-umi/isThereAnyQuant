package org.shiroumi.strategy.breakdown

/**
 * 回归通道几何破位检测（逐行移植 temp/zigzag_base.py 的 detect_breakdown_zigzag，v4 口径，2026-06-16 锁定）。
 *
 * SSOT v4 几何（用户拍板，回归通道而非 pivot）：
 *   长轴下沿 = 长窗 low 回归（破位靶）；长轴上沿 = 长窗 high 回归（趋势交点用）；
 *   短轴下沿 = 短窗 low 回归（趋势苗头）。
 *   条件①趋势：短轴下沿 a_st<0（近期转弱）即成立（_force_threshold 与 a_up<=0 已被作者删除，不移植）。
 *   条件②破位：当日【新跌穿】长轴下沿 cross_below = (pc>=下沿@t-1) && (c_t<下沿@t)，裸比较禁 EPS。
 *   ①∧② → proto='zz_down'，lvl=长轴下沿@t 经 floor/ceil clip 冻结标量。
 *
 * 因果：回归窗 [t-W+1, t] 只用 t 及之前 high/low/close（t 日盘后已知），零未来数据。
 * emit_up=false：生产不开向上突破诊断分支。
 */

// ===== 合法边界与单位换算常数（非可调旋钮，与 zigzag_base.py 焊死一致）=====
const val W_SHORT = 5            // 短轴回归窗（短期苗头）
const val W_LONG = 20           // 长轴回归窗（大方向背景）
const val PERIOD_ATR = 14
const val RECOVER_WIN = 3       // 反撞收复观察窗（breakdown_base.RECOVER_WIN）
const val BRK_MIN_DROP = 0.02   // 破位最小当日跌幅（力度门槛常量，v4 主判据已不用，保留口径常量）

private const val SUSPEND_JUMP = 1.20   // 停牌跳变过滤阈值（相邻收盘比值 abs(r)>1.20）

/**
 * OHLC 列存（一只票的连续有效序列，与 Python arr=(dd,oo,hh,ll,cc) 同构）。
 * open/high/low/close 全程 Double（close_qfq.toDouble()），下标与 dates 对齐。
 */
class BreakdownArr(
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
) {
    val n: Int get() = close.size
}

/** 破位事件：被跌穿的结构位 lvl + 原型 proto（=zz_down）。 */
data class BreakdownEvent(val lvl: Double, val proto: String)

/**
 * 对 series[t-W+1 .. t] 线性回归，返回全局坐标直线 value@g=a*g+b，或 null。
 * 移植 zigzag_base._rail：复用 linregChannel（窗内相对坐标），转全局：
 *   value@g = intercept + slope*(g-(t-lb+1)) = slope*g + (intercept - slope*(t-lb+1))。
 * lb = min(W, t+1)，且 lb<3 返回 null。
 */
internal data class Rail(val a: Double, val b: Double, val residStd: Double)

internal fun rail(series: DoubleArray, t: Int, w: Int): Rail? {
    val lb = minOf(w, t + 1)
    if (lb < 3) return null
    val ch = linregChannel(series, t, lb)
    if (!ch.slope.isFinite() || !ch.intercept.isFinite()) return null
    val a = ch.slope
    val b = ch.intercept - a * (t - lb + 1)
    return Rail(a, b, ch.residStd)
}

/**
 * 条件①趋势（移植 zigzag_base._trend_ok，用户拍板 2026-06-16）：
 * 只判短轴下沿 a_st<0（近期转弱）即成立。a_up<=0 限制已删，不移植。
 * x_cross（短轴下沿×长轴上沿交点）仅作 feats 诊断量，不进硬判据。
 * 返回 (ok, xCross)；不成立时 xCross 无意义。
 */
internal data class TrendResult(val ok: Boolean, val xCross: Double)

internal fun trendOk(aSt: Double?, aUp: Double?, bSt: Double?, bUp: Double?, t: Int, w: Int): TrendResult {
    if (aSt == null || aSt >= 0.0) return TrendResult(false, Double.POSITIVE_INFINITY)
    // 短轴下沿向下 = 近期转弱，趋势条件成立
    if (aUp == null || bSt == null || bUp == null) return TrendResult(true, Double.NEGATIVE_INFINITY)
    val denom = aSt - aUp
    if (kotlin.math.abs(denom) < 1e-12) return TrendResult(true, Double.POSITIVE_INFINITY)
    val raw = (bUp - bSt) / denom
    val lo = t - 3.0 * w
    val hi = t + 3.0 * w
    val xCross = if (raw < lo) lo else if (raw > hi) hi else raw
    return TrendResult(true, xCross)
}

/**
 * is_strong（移植 zigzag_base._is_strong）：长窗回归上行（slope>0）且通道窄
 * （resid_std/price 落 [start,t] 历史 10% 低分位）。仅作 regime 标记，不影响 lvl。
 */
internal fun isStrong(close: DoubleArray, t: Int): Boolean {
    val rl = rail(close, t, W_LONG) ?: return false
    if (rl.a <= 0.0) return false
    val price = if (close[t] > 0.0) close[t] else 1e-9
    val narrowNow = rl.residStd / price
    val start = maxOf(0, t - 240)
    val hist = ArrayList<Double>()
    for (g in (start + W_LONG)..t) {
        val c = rail(close, g, W_LONG)
        if (c != null && close[g] > 0.0) hist.add(c.residStd / close[g])
    }
    if (hist.size < 10) return false
    return narrowNow <= quantile(hist, 0.10)
}

/**
 * 回归通道几何破位检测（移植 detect_breakdown_zigzag）。返回 null 或 BreakdownEvent(lvl, 'zz_down')。
 * 严格只用 t 及之前 + 当日。emit_up=false 生产不开向上突破分支。
 */
fun detectBreakdownZigzag(arr: BreakdownArr, t: Int): BreakdownEvent? {
    val dd = arr.close
    val oo = arr.open
    val hh = arr.high
    val ll = arr.low
    val cc = arr.close
    val n = arr.n
    // t < W_LONG+1（即 t<21）整体 return null
    if (t < W_LONG + 1 || t >= n) return null
    val oT = oo[t]; val cT = cc[t]; val pc = cc[t - 1]; val lT = ll[t]
    if (!(oT > 0.0 && cT > 0.0 && pc > 0.0 && lT > 0.0)) return null

    // 停牌跳变过滤：cc[max(0,t-W_LONG):t+1] 相邻比值 abs(r)>1.20 任一即 null
    run {
        val lo = maxOf(0, t - W_LONG)
        var prev = cc[lo]
        for (i in (lo + 1)..t) {
            val cur = cc[i]
            val r = cur / prev - 1.0
            if (kotlin.math.abs(r) > SUSPEND_JUMP) return null
            prev = cur
        }
    }

    // 力度门槛已删（用户拍板 2026-06-16）：跌穿下沿本身即破位信号，不再要求当日跌幅。

    // ---- 回归通道：短轴(短窗) + 长轴(长窗)，上沿拟 high / 下沿拟 low ----
    val longLo = rail(ll, t, W_LONG) ?: return null  // 长轴下沿（破位靶）
    val longUp = rail(hh, t, W_LONG)                  // 长轴上沿（趋势交点用）
    val shortLo = rail(ll, t, W_SHORT)                // 短轴下沿（趋势苗头）
    val aLo = longLo.a; val bLo = longLo.b
    val aUp = longUp?.a; val bUp = longUp?.b
    val aSt = shortLo?.a; val bSt = shortLo?.b

    val loAtT = aLo * t + bLo
    val loAtTm1 = aLo * (t - 1) + bLo

    // ---- 条件② 新跌穿长轴下沿（cross-below），裸比较禁 EPS ----
    // lo_at_tm1 用 t 日窗回归的同一条下沿在 t-1 处取值（不是 t-1 日重新回归）。
    val crossBelow = (pc >= loAtTm1) && (cT < loAtT)
    if (!crossBelow) return null

    // ---- 条件① 趋势（短轴下沿 a_st<0 近期转弱；力度门槛已删）----
    val (ok, _) = trendOk(aSt, aUp, bSt, bUp, t, W_LONG)
    if (!ok) return null

    // ---- lvl 物理边界（floor/ceil 因果可见；clip 后须 >c_t 否则收复语义失效）----
    // floor = min(ll[max(0,t-20):t+1]); ceil = pc; ceil<floor 时 ceil=max(floor,lo_at_t)
    var floor = Double.POSITIVE_INFINITY
    val flo = maxOf(0, t - W_LONG)
    for (i in flo..t) if (ll[i] < floor) floor = ll[i]
    var ceil = pc
    if (ceil < floor) ceil = maxOf(floor, loAtT)
    val lvlRaw = loAtT
    val lvl = if (lvlRaw < floor) floor else if (lvlRaw > ceil) ceil else lvlRaw
    if (!(lvl > cT && cT > 0.0)) return null

    return BreakdownEvent(lvl = lvl, proto = "zz_down")
}

/**
 * numpy.quantile 线性插值（method='linear'，与 np.quantile 默认口径一致）。
 * @param values 非空数据（内部排序）。
 * @param q 分位 [0,1]。
 */
internal fun quantile(values: List<Double>, q: Double): Double {
    val sorted = values.sorted()
    val m = sorted.size
    if (m == 1) return sorted[0]
    val pos = q * (m - 1)
    val lo = pos.toInt()
    val hi = if (lo + 1 < m) lo + 1 else lo
    val frac = pos - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

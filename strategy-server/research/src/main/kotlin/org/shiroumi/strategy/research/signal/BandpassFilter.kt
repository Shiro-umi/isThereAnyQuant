package org.shiroumi.strategy.research.signal

import org.apache.commons.math3.complex.Complex
import kotlin.math.PI
import kotlin.math.tan

/**
 * Butterworth 带通滤波器 + 零相位 / 因果两种应用方式。
 *
 * 对应执行手册 §4：Butterworth 4 阶；样本内发现阶段用 [filtfilt]（双向，零相位），
 * 验证阶段用 [lfilter]（单向，因果），两者结果都要保留作为"是否依赖未来函数"的对照。
 *
 * 设计系数（[design]）算法对齐 `scipy.signal.butter(N, [lo,hi], btype='band')`：
 *   1. 低通原型极点（左半平面单位圆上均匀分布）；
 *   2. lowpass → bandpass 频率变换（阶数翻倍：N 阶带通有 2N 个极点）；
 *   3. 双线性变换离散化（含预畸变 warp）。
 *
 * 频率以归一化形式给出：Wn ∈ (0,1)，1 对应 Nyquist（采样率的一半）。
 * 研究里采样间隔为 1 个交易日，Nyquist = 0.5 cycle/day，故频带 [f_lo, f_hi] cycle/day
 * 对应 Wn = [f_lo, f_hi] / 0.5。
 */
object BandpassFilter {

    /**
     * 数字滤波器系数 `b`（分子）/`a`（分母），形如：
     * `a[0]·y[n] = Σ b[i]·x[n-i] − Σ a[j]·y[n-j]`，其中 a[0] 已归一化为 1。
     */
    data class Coefficients(val b: DoubleArray, val a: DoubleArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Coefficients && b.contentEquals(other.b) && a.contentEquals(other.a))

        override fun hashCode(): Int = b.contentHashCode() * 31 + a.contentHashCode()
    }

    /**
     * 设计 [order] 阶 Butterworth 带通滤波器。
     *
     * @param order  原型阶数 N（手册：4）；带通实际阶数为 2N
     * @param lowWn  低截止，归一化频率 ∈ (0,1)
     * @param highWn 高截止，归一化频率 ∈ (0,1)，须 > lowWn
     */
    fun design(order: Int, lowWn: Double, highWn: Double): Coefficients {
        require(order >= 1) { "阶数必须 >= 1：order=$order" }
        require(lowWn > 0.0 && highWn < 1.0 && lowWn < highWn) {
            "归一化频带非法：lowWn=$lowWn, highWn=$highWn（要求 0 < lowWn < highWn < 1）"
        }

        // 1. 预畸变到模拟角频率（双线性变换，采样周期取 fs=2 → T=1 的标准约定）
        val warpedLow = tan(PI * lowWn / 2.0)
        val warpedHigh = tan(PI * highWn / 2.0)
        val bw = warpedHigh - warpedLow            // 带宽
        val w0Sq = warpedLow * warpedHigh          // 中心频率平方

        // 2. 低通原型极点（归一化截止 = 1），共 order 个，位于左半平面
        val protoPoles = Array(order) { k ->
            val theta = PI * (2.0 * k + 1.0) / (2.0 * order) + PI / 2.0
            Complex(Math.cos(theta), Math.sin(theta))
        }

        // 3. lowpass(s) -> bandpass: 每个原型极点 p 映射为二次方程
        //    s² - (p·bw)·s + w0² = 0 的两个根，得到 2·order 个带通极点。
        val poles = ArrayList<Complex>(2 * order)
        for (p in protoPoles) {
            val pb = p.multiply(bw / 2.0)
            val disc = pb.multiply(pb).subtract(Complex(w0Sq, 0.0)).sqrt()
            poles.add(pb.add(disc))
            poles.add(pb.subtract(disc))
        }
        // 带通在 s=0 和 s=∞ 各引入 order 个零点；模拟带通有 order 个零点在原点。
        val zeros = Array(order) { Complex.ZERO }

        // 4. 双线性变换 z = (1 + s)/(1 - s)（T=1 约定下 2/T=2，已并入预畸变缩放）
        val (zb, za, gain) = bilinear(zeros, poles.toTypedArray(), order)

        // 5. 归一化增益：使带通在中心频率处幅度为 1
        val wCenter = (lowWn + highWn) / 2.0
        val gResp = freqResponseMagnitude(zb, za, wCenter)
        val scale = if (gResp > 0.0) 1.0 / gResp else 1.0
        val bNorm = DoubleArray(zb.size) { zb[it] * gain * scale }

        return Coefficients(b = bNorm, a = za)
    }

    /**
     * 因果（单向）IIR 滤波，直接 II 型转置实现，等价 `scipy.signal.lfilter`。
     * 有相位延迟，用于验证阶段（不引入未来信息）。
     *
     * @param zi 初始延迟状态（长度 order-1）；null 表示零初值（冷启动）。
     *           [filtfilt] 会传入由 [lfilterZi] 缩放的稳态初值以匹配 scipy。
     */
    fun lfilter(coeff: Coefficients, x: DoubleArray, zi: DoubleArray? = null): DoubleArray {
        val (b, a) = coeff
        val n = x.size
        val y = DoubleArray(n)
        val order = maxOf(b.size, a.size)
        val z = DoubleArray(order)
        if (zi != null) zi.copyInto(z, endIndex = minOf(zi.size, order))
        for (i in 0 until n) {
            val xi = x[i]
            val yi = b[0] * xi + z[0]
            for (j in 1 until order) {
                val bj = if (j < b.size) b[j] else 0.0
                val aj = if (j < a.size) a[j] else 0.0
                z[j - 1] = bj * xi + z[j] - aj * yi
            }
            y[i] = yi
        }
        return y
    }

    /**
     * 稳态初始延迟状态，等价 `scipy.signal.lfilter_zi`。
     *
     * 转置型直接 II 型的状态递推稳态满足线性系统 `(I − A)·zi = B`，其中（系数按 a[0]=1 归一化）：
     * - 状态转移矩阵 A：首列 `A[i][0] = −a[i+1]`，次对角 `A[i][i+1] = 1`（i < m−1）；
     * - 右端项 `B[i] = b[i+1] − a[i+1]·b[0]`。
     *
     * 用 LU 分解解此 m×m 系统（m = order−1）。不能用简单回代，因为首列的 −a 项把各状态耦合在一起。
     */
    fun lfilterZi(coeff: Coefficients): DoubleArray {
        val order = maxOf(coeff.a.size, coeff.b.size)
        val m = order - 1
        val a = DoubleArray(order) { if (it < coeff.a.size) coeff.a[it] else 0.0 }
        val b = DoubleArray(order) { if (it < coeff.b.size) coeff.b[it] else 0.0 }

        val iMinusA = Array(m) { i -> DoubleArray(m) { j -> if (i == j) 1.0 else 0.0 } }
        for (i in 0 until m) {
            iMinusA[i][0] -= -a[i + 1]            // 减去 A 的首列
            if (i + 1 < m) iMinusA[i][i + 1] -= 1.0 // 减去 A 的次对角
        }
        val rhs = DoubleArray(m) { b[it + 1] - a[it + 1] * b[0] }

        val matrix = org.apache.commons.math3.linear.Array2DRowRealMatrix(iMinusA, false)
        val solver = org.apache.commons.math3.linear.LUDecomposition(matrix).solver
        val solution = solver.solve(org.apache.commons.math3.linear.ArrayRealVector(rhs, false))
        return solution.toArray()
    }

    /**
     * 零相位双向滤波，等价 `scipy.signal.filtfilt`（默认 padtype='odd' + 稳态初值）。
     * 与 scipy 默认完全一致：`padlen = 3·max(len(a), len(b))` 的 odd 反射延拓，每遍 lfilter
     * 以 [lfilterZi] 稳态初值乘边界样本启动，正向滤 → 反转 → 再滤 → 反转，相位完全抵消。用于发现阶段。
     */
    fun filtfilt(coeff: Coefficients, x: DoubleArray): DoubleArray {
        val ntaps = maxOf(coeff.a.size, coeff.b.size)
        val padlen = 3 * ntaps
        require(x.size > padlen) { "信号过短，无法 filtfilt：len=${x.size}, 需要 > $padlen" }

        val ext = oddReflectPad(x, padlen)
        val zi = lfilterZi(coeff)
        // 正向：以延拓序列首值缩放的稳态初值启动（scipy filtfilt 默认行为）
        val once = lfilter(coeff, ext, scale(zi, ext.first()))
        once.reverse()
        // 反向：以反转后序列首值（即正向末值）缩放的稳态初值启动
        val twice = lfilter(coeff, once, scale(zi, once.first()))
        twice.reverse()
        // 去掉两端延拓
        return twice.copyOfRange(padlen, twice.size - padlen)
    }

    // ---- 内部数学 ----

    /** 把稳态初值 zi 按边界样本值缩放：`zi · edge`（scipy filtfilt 的初始条件）。 */
    private fun scale(zi: DoubleArray, edge: Double): DoubleArray =
        DoubleArray(zi.size) { zi[it] * edge }

    /** odd 反射延拓：两端各补 [padlen] 个点，`2·edge - x[k]`，与 scipy padtype='odd' 一致。 */
    private fun oddReflectPad(x: DoubleArray, padlen: Int): DoubleArray {
        val n = x.size
        val out = DoubleArray(n + 2 * padlen)
        for (i in 0 until padlen) out[i] = 2.0 * x[0] - x[padlen - i]
        x.copyInto(out, padlen)
        for (i in 0 until padlen) out[padlen + n + i] = 2.0 * x[n - 1] - x[n - 2 - i]
        return out
    }

    /**
     * 双线性变换：把 s 平面的零极点映射到 z 平面，返回 (分子系数 b, 分母系数 a, 实增益)。
     * z = (1 + s) / (1 - s)（T=1，预畸变已在调用方处理）。
     */
    private fun bilinear(
        zerosS: Array<Complex>,
        polesS: Array<Complex>,
        order: Int,
    ): Triple<DoubleArray, DoubleArray, Double> {
        val one = Complex(1.0, 0.0)
        fun mapToZ(s: Complex): Complex = one.add(s).divide(one.subtract(s))

        val zerosZ = ArrayList<Complex>()
        for (z in zerosS) zerosZ.add(mapToZ(z))
        // s=∞ 处的零点（带通：分子分母同阶差，补 z=-1 零点补足阶数）
        val nMissing = polesS.size - zerosS.size
        repeat(nMissing) { zerosZ.add(Complex(-1.0, 0.0)) }

        val polesZ = polesS.map { mapToZ(it) }

        // 由根构造多项式系数（实序列）
        val bPoly = polyFromRoots(zerosZ)
        val aPoly = polyFromRoots(polesZ)

        // 增益：模拟原型在 s=0 处增益为 1（低通），带通经变换后统一在调用方归一化。
        // 这里给出双线性的标量增益 = Π(1 - z_pole) / Π(1 - z_zero) 的实部近似，
        // 真实幅度由调用方按中心频率二次归一化，故此处取 1。
        val gain = 1.0

        return Triple(bPoly, aPoly, gain)
    }

    /** 由复根构造实系数多项式 `Π(x - r)`，结果虚部应接近 0（共轭成对）。 */
    private fun polyFromRoots(roots: List<Complex>): DoubleArray {
        var coeffs = arrayOf(Complex(1.0, 0.0))
        for (r in roots) {
            val next = Array(coeffs.size + 1) { Complex.ZERO }
            for (i in coeffs.indices) {
                next[i] = next[i].add(coeffs[i])
                next[i + 1] = next[i + 1].subtract(coeffs[i].multiply(r))
            }
            coeffs = next
        }
        return DoubleArray(coeffs.size) { coeffs[it].real }
    }

    /** |H(e^{jω})| at normalized frequency Wn∈(0,1)（1=Nyquist）。 */
    private fun freqResponseMagnitude(b: DoubleArray, a: DoubleArray, wn: Double): Double {
        val omega = PI * wn
        val z = Complex(Math.cos(omega), Math.sin(omega)) // e^{jω}
        fun evalPoly(c: DoubleArray): Complex {
            // c[0] + c[1]z^-1 + ... = Σ c[k] z^-k
            var acc = Complex.ZERO
            var zk = Complex(1.0, 0.0) // z^0
            val zInv = z.reciprocal()
            for (k in c.indices) {
                acc = acc.add(zk.multiply(c[k]))
                zk = zk.multiply(zInv)
            }
            return acc
        }
        val num = evalPoly(b)
        val den = evalPoly(a)
        return num.divide(den).abs()
    }
}

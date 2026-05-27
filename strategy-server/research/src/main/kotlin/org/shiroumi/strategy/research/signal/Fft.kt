package org.shiroumi.strategy.research.signal

import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType

/**
 * 离散傅里叶变换 —— 包装 Commons Math 的 [FastFourierTransformer]。
 *
 * Commons Math 的 FFT 要求长度为 2 的幂。本封装提供：
 * - 自动补零到 2 的幂（[fftPadded]），匹配研究里 n_fft=64 的定长 FFT 约定；
 * - 直接对 2 的幂长度做正/逆变换。
 *
 * 归一化采用 [DftNormalization.STANDARD]（正变换不缩放，逆变换除 N），与 numpy/scipy 的 fft/ifft 一致，
 * 便于 T6 与 scipy 数值对照。
 */
object Fft {

    private val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    /** 大于等于 [n] 的最小 2 的幂。 */
    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0) { "长度必须为正：n=$n" }
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** 对已是 2 的幂长度的实序列做正向 FFT。 */
    fun forward(real: DoubleArray): Array<Complex> {
        require(isPowerOfTwo(real.size)) { "FFT 长度必须是 2 的幂：size=${real.size}" }
        return transformer.transform(real, TransformType.FORWARD)
    }

    /**
     * 对实序列补零到指定 [nFft]（默认补到最近的 2 的幂）后做正向 FFT。
     *
     * @param real 实数输入（如加窗后的片段）
     * @param nFft 目标 FFT 长度，须为 2 的幂且 >= real.size；默认取 `nextPowerOfTwo(real.size)`
     */
    fun fftPadded(real: DoubleArray, nFft: Int = nextPowerOfTwo(real.size)): Array<Complex> {
        require(isPowerOfTwo(nFft)) { "n_fft 必须是 2 的幂：n_fft=$nFft" }
        require(nFft >= real.size) { "n_fft=$nFft 小于输入长度 ${real.size}" }
        val padded = DoubleArray(nFft)
        real.copyInto(padded)
        return transformer.transform(padded, TransformType.FORWARD)
    }

    /** 逆向 FFT，返回复数序列（已按 STANDARD 归一化除 N）。 */
    fun inverse(spectrum: Array<Complex>): Array<Complex> {
        require(isPowerOfTwo(spectrum.size)) { "iFFT 长度必须是 2 的幂：size=${spectrum.size}" }
        return transformer.transform(spectrum, TransformType.INVERSE)
    }

    /**
     * 单边频率轴（cycle/sample），长度 `nFft/2 + 1`。
     * 第 k 个 bin 对应频率 `k / nFft`（采样间隔为 1 个交易日时即 cycle/day）。
     */
    fun frequencies(nFft: Int): DoubleArray {
        val half = nFft / 2
        return DoubleArray(half + 1) { it.toDouble() / nFft }
    }

    private fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
}

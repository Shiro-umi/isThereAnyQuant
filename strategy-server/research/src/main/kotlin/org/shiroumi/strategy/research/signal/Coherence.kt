package org.shiroumi.strategy.research.signal

import org.apache.commons.math3.complex.Complex
import kotlin.math.atan2

/**
 * 幅度平方相干性（Magnitude-Squared Coherence），Welch 分段平均法。
 *
 * Commons Math 不提供 coherence，这里自实现，算法对齐 `scipy.signal.coherence`：
 *
 * ```
 * 把 x, y 各切成长度 nperseg、重叠 noverlap 的段；每段去均值 → Hann 加窗 → FFT；
 * 跨段平均：⟨Pxx⟩, ⟨Pyy⟩, ⟨Pxy⟩
 * γ²(ω) = |⟨Pxy⟩|² / (⟨Pxx⟩ · ⟨Pyy⟩)
 * φ(ω)  = arg(⟨Pxy⟩)
 * ```
 *
 * 分段平均是相干性有意义的前提：单段算出的 γ² 恒为 1。窗归一化常数（Σw²）在分子分母对消，
 * 故 γ² 与归一化无关；这与 scipy 一致，便于 T6 数值对照。
 *
 * 这是纯数学原语，不含频带/因子语义。手册 §6.3 的频带聚合 `Coh_B(t)` 由研究内容在此之上组合。
 */
object Coherence {

    /**
     * 相干性结果（单边谱）。
     *
     * @property frequencies 单边频率轴（cycle/sample），长度 `nperseg/2 + 1`
     * @property coherence   各频点的 γ²(ω) ∈ [0,1]
     * @property phase       各频点互谱相位 φ(ω) ∈ (-π, π]，正值表示 x 领先 y
     */
    data class Result(
        val frequencies: DoubleArray,
        val coherence: DoubleArray,
        val phase: DoubleArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Result &&
                    frequencies.contentEquals(other.frequencies) &&
                    coherence.contentEquals(other.coherence) &&
                    phase.contentEquals(other.phase))

        override fun hashCode(): Int {
            var h = frequencies.contentHashCode()
            h = h * 31 + coherence.contentHashCode()
            h = h * 31 + phase.contentHashCode()
            return h
        }
    }

    /**
     * 计算 x 与 y 的幅度平方相干性。
     *
     * @param x 实数信号
     * @param y 实数信号（须与 x 等长）
     * @param nperseg  每段长度（默认 256，与 scipy 默认一致语义；研究里按需传窗长）
     * @param noverlap 段间重叠（默认 nperseg/2，即 scipy 默认的 50% 重叠）
     */
    fun compute(
        x: DoubleArray,
        y: DoubleArray,
        nperseg: Int = 256,
        noverlap: Int = nperseg / 2,
    ): Result {
        require(x.size == y.size) { "x、y 长度不一致：${x.size} vs ${y.size}" }
        val seg = minOf(nperseg, x.size)
        require(seg >= 2) { "信号过短，无法分段：size=${x.size}" }
        require(noverlap in 0 until seg) { "noverlap=$noverlap 必须在 [0, $seg)" }

        val nFft = Fft.nextPowerOfTwo(seg)
        val half = nFft / 2
        val hann = Window.hann(seg)
        val step = seg - noverlap

        val pxx = DoubleArray(half + 1)
        val pyy = DoubleArray(half + 1)
        val pxyRe = DoubleArray(half + 1)
        val pxyIm = DoubleArray(half + 1)

        var segments = 0
        var start = 0
        while (start + seg <= x.size) {
            val sx = detrendAndWindow(x, start, seg, hann)
            val sy = detrendAndWindow(y, start, seg, hann)
            val fx = Fft.fftPadded(sx, nFft)
            val fy = Fft.fftPadded(sy, nFft)

            for (k in 0..half) {
                pxx[k] += fx[k].power()
                pyy[k] += fy[k].power()
                val cxy = crossSpectrum(fx[k], fy[k]) // x·conj(y)
                pxyRe[k] += cxy.real
                pxyIm[k] += cxy.imaginary
            }
            segments++
            start += step
        }
        require(segments > 0) { "无有效分段：nperseg=$seg 大于信号长度 ${x.size}" }

        val coh = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        for (k in 0..half) {
            val cross = Complex(pxyRe[k], pxyIm[k])
            val denom = pxx[k] * pyy[k]
            coh[k] = if (denom > 0.0) cross.power() / denom else 0.0
            phase[k] = atan2(pxyIm[k], pxyRe[k])
        }

        return Result(frequencies = Fft.frequencies(nFft), coherence = coh, phase = phase)
    }

    private fun detrendAndWindow(src: DoubleArray, start: Int, len: Int, win: DoubleArray): DoubleArray {
        val seg = DoubleArray(len) { src[start + it] }
        val mean = seg.average()
        for (i in seg.indices) seg[i] = (seg[i] - mean) * win[i]
        return seg
    }
}

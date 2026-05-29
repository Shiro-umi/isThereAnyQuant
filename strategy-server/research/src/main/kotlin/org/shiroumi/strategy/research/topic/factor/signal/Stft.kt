package org.shiroumi.strategy.research.topic.factor.signal

import org.apache.commons.math3.complex.Complex

/**
 * 短时傅里叶变换（STFT）：滑窗 + Hann 加窗 + 定长 FFT。
 *
 * 对应执行手册 §6.1：窗口长度 30/40/60、步长 1、Hann 窗、n_fft 64。
 *
 * 输出是一组「帧」，每帧是一个窗口位置上的单边复数谱。两条同步信号各自做 STFT 后，
 * 即可在每个 (帧, 频点) 上构造互谱与功率谱，供 [Coherence] 聚合出相干性与相位（§6.2/§6.3）。
 *
 * 设计为纯数学原语：只负责"把信号变成时频谱"，不含任何因子/情绪语义，也不做平滑——
 * 平滑/频带聚合的口径由调用方（研究内容）按手册选择。
 */
object Stft {

    /**
     * STFT 一帧的结果。
     *
     * @property frameIndex 帧序号（从 0 开始）
     * @property centerIndex 该窗口中心在原序列中的样本下标（用于把时频结果映射回交易日）
     * @property spectrum 单边复数谱，长度 `nFft/2 + 1`
     */
    data class Frame(
        val frameIndex: Int,
        val centerIndex: Int,
        val spectrum: Array<Complex>,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Frame &&
                    frameIndex == other.frameIndex &&
                    centerIndex == other.centerIndex &&
                    spectrum.contentEquals(other.spectrum))

        override fun hashCode(): Int =
            (frameIndex * 31 + centerIndex) * 31 + spectrum.contentHashCode()
    }

    /**
     * STFT 完整结果：一组帧 + 共享的单边频率轴。
     *
     * @property frames 各窗口位置的谱帧（按时间升序）
     * @property frequencies 单边频率轴（cycle/sample），长度 `nFft/2 + 1`
     * @property window 窗口长度
     * @property step 步长
     * @property nFft FFT 长度
     */
    data class Result(
        val frames: List<Frame>,
        val frequencies: DoubleArray,
        val window: Int,
        val step: Int,
        val nFft: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Result &&
                    frames == other.frames &&
                    frequencies.contentEquals(other.frequencies) &&
                    window == other.window && step == other.step && nFft == other.nFft)

        override fun hashCode(): Int {
            var h = frames.hashCode()
            h = h * 31 + frequencies.contentHashCode()
            h = h * 31 + window
            h = h * 31 + step
            h = h * 31 + nFft
            return h
        }
    }

    /**
     * 对实序列做 STFT。
     *
     * 每个窗口位置：取 [window] 个样本 → 去窗内均值（去直流，匹配 scipy `detrend='constant'` 的常见用法）
     * → 乘 Hann 窗 → 补零到 [nFft] → FFT → 取单边谱。
     *
     * @param signal 实数输入序列
     * @param window 窗口长度（手册：30/40/60）
     * @param step   步长（手册：1）
     * @param nFft   FFT 长度（手册：64），须为 2 的幂且 >= window
     * @param detrend 是否在加窗前去窗内均值（默认 true）
     */
    fun transform(
        signal: DoubleArray,
        window: Int,
        step: Int = 1,
        nFft: Int = 64,
        detrend: Boolean = true,
    ): Result {
        require(window in 1..signal.size) { "窗口长度 $window 超出信号长度 ${signal.size}" }
        require(step >= 1) { "步长必须 >= 1：step=$step" }
        require(nFft >= window) { "n_fft=$nFft 小于窗口长度 $window" }

        val hann = Window.hann(window)
        val frames = ArrayList<Frame>()
        var frameIndex = 0
        var start = 0
        while (start + window <= signal.size) {
            val seg = DoubleArray(window) { signal[start + it] }
            if (detrend) {
                val mean = seg.average()
                for (i in seg.indices) seg[i] -= mean
            }
            for (i in seg.indices) seg[i] *= hann[i]

            val full = Fft.fftPadded(seg, nFft)
            val half = nFft / 2
            val oneSided = Array(half + 1) { full[it] }

            frames.add(Frame(frameIndex, centerIndex = start + window / 2, spectrum = oneSided))
            frameIndex++
            start += step
        }

        return Result(
            frames = frames,
            frequencies = Fft.frequencies(nFft),
            window = window,
            step = step,
            nFft = nFft,
        )
    }
}

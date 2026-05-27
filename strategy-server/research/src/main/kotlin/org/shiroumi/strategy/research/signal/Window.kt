package org.shiroumi.strategy.research.signal

import kotlin.math.PI
import kotlin.math.cos

/**
 * 窗函数。
 *
 * STFT 谱分析使用 **periodic（周期性）Hann 窗**，与 scipy.signal 默认（`sym=False`）一致：
 *
 * ```
 * w[n] = 0.5 - 0.5 · cos(2πn / N),  n = 0..N-1
 * ```
 *
 * 注意 periodic 与 symmetric 的区别：分母用 N 而非 N-1。谱分析必须用 periodic，
 * 否则与 scipy 的 STFT/谱估计结果有系统性偏差，T6 对照会失败。
 */
object Window {

    /** 长度为 [n] 的 periodic Hann 窗。 */
    fun hann(n: Int): DoubleArray {
        require(n > 0) { "窗长必须为正：n=$n" }
        if (n == 1) return doubleArrayOf(1.0)
        return DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    }
}

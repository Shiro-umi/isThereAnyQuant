package org.shiroumi.strategy.research.signal

import org.apache.commons.math3.complex.Complex

/**
 * 复数工具：本模块统一复用 Apache Commons Math 的 [Complex]，这里只补研究常用的便捷扩展。
 *
 * 选择复用而非自造，是为了让 FFT（[Fft]）输入输出与频域计算（[Coherence]）共享同一复数类型，
 * 避免在边界处反复装箱转换。
 */

/** 复数的功率（模平方）：`|z|² = re² + im²`。 */
fun Complex.power(): Double = real * real + imaginary * imaginary

/** 互谱密度的单段贡献：`x · conj(y)`。 */
fun crossSpectrum(x: Complex, y: Complex): Complex = x.multiply(y.conjugate())

/** 把实数序列包装为复数序列（虚部置零），供 FFT 使用。 */
fun DoubleArray.toComplex(): Array<Complex> = Array(size) { Complex(this[it], 0.0) }

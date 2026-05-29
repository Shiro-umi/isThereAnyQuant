package org.shiroumi.strategy.research.topic.factor.signal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 信号层 scipy 数值对照测试（T6）。
 *
 * 读取 `temp/scipy_golden.json`（由 temp/scipy_golden.py 用 scipy 生成），
 * 用完全相同的合成信号在 Kotlin 自实现上重算，断言与 scipy 的逐元素误差在容差内。
 *
 * 合成信号、噪声完全来自黄金 JSON（噪声直接读入，避免跨语言 RNG 差异），
 * 保证两侧输入逐位一致，差异只可能来自算法实现。
 */
class SignalCrossValidationTest {

    private lateinit var golden: JsonObject
    private val N = 512
    private val fs = 1.0

    private lateinit var x: DoubleArray
    private lateinit var y: DoubleArray

    @BeforeTest
    fun setup() {
        val text = javaClass.getResourceAsStream("/scipy_golden.json")?.bufferedReader()?.readText()
            ?: error("找不到测试资源 /scipy_golden.json（由 temp/scipy_golden.py 生成后固化到 src/test/resources）")
        golden = Json.parseToJsonElement(text).jsonObject
        val noiseX = doubleArr("noise_x")
        val noiseY = doubleArr("noise_y")
        x = DoubleArray(N) { n ->
            sin(2 * PI * 0.25 * n) + 0.5 * sin(2 * PI * (1.0 / 7) * n) + noiseX[n]
        }
        y = DoubleArray(N) { n ->
            sin(2 * PI * 0.25 * n - 0.6) + 0.5 * sin(2 * PI * (1.0 / 7) * n - 0.6) + noiseY[n]
        }
    }

    @Test
    fun `hann window matches scipy periodic`() {
        for (w in intArrayOf(30, 40, 60)) {
            val expected = doubleArr("hann_$w")
            val actual = Window.hann(w)
            assertClose("hann_$w", expected, actual, tol = 1e-12)
        }
    }

    @Test
    fun `fft of windowed segment matches numpy`() {
        val seg = DoubleArray(40) { x[it] }
        val mean = seg.average()
        val hann = Window.hann(40)
        val prepared = DoubleArray(40) { (seg[it] - mean) * hann[it] }
        val spec = Fft.fftPadded(prepared, 64)
        val half = 32
        val expRe = doubleArr("fft_seg_real")
        val expIm = doubleArr("fft_seg_imag")
        val actRe = DoubleArray(half + 1) { spec[it].real }
        val actIm = DoubleArray(half + 1) { spec[it].imaginary }
        assertClose("fft_real", expRe, actRe, tol = 1e-9)
        assertClose("fft_imag", expIm, actIm, tol = 1e-9)
    }

    @Test
    fun `welch coherence matches scipy`() {
        for (nperseg in intArrayOf(64, 128)) {
            val expected = doubleArr("coherence_${nperseg}_cxy")
            val res = Coherence.compute(x, y, nperseg = nperseg, noverlap = nperseg / 2)
            assertClose("coherence_$nperseg", expected, res.coherence, tol = 1e-9)
        }
    }

    @Test
    fun `butterworth bandpass coefficients match scipy`() {
        val expB = doubleArr("butter_b")
        val expA = doubleArr("butter_a")
        // F2a: 5-8 天 → Wn=[0.25,0.40]
        val coeff = BandpassFilter.design(order = 4, lowWn = 0.25, highWn = 0.40)
        assertClose("butter_b", expB, coeff.b, tol = 1e-6)
        assertClose("butter_a", expA, coeff.a, tol = 1e-6)
    }

    @Test
    fun `lfilter matches scipy`() {
        val coeff = BandpassFilter.Coefficients(b = doubleArr("butter_b"), a = doubleArr("butter_a"))
        val actual = BandpassFilter.lfilter(coeff, x)
        assertClose("lfilter", doubleArr("lfilter_x"), actual, tol = 1e-9)
    }

    @Test
    fun `filtfilt matches scipy`() {
        val coeff = BandpassFilter.Coefficients(b = doubleArr("butter_b"), a = doubleArr("butter_a"))
        val actual = BandpassFilter.filtfilt(coeff, x)
        assertClose("filtfilt", doubleArr("filtfilt_x"), actual, tol = 1e-6)
    }

    // ---- helpers ----

    private fun doubleArr(key: String): DoubleArray =
        golden[key]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    private fun assertClose(name: String, expected: DoubleArray, actual: DoubleArray, tol: Double) {
        assertTrue(expected.size == actual.size, "$name 长度不一致：${expected.size} vs ${actual.size}")
        var maxErr = 0.0
        var at = -1
        for (i in expected.indices) {
            val e = abs(expected[i] - actual[i])
            if (e > maxErr) { maxErr = e; at = i }
        }
        assertTrue(maxErr <= tol, "$name 最大误差 $maxErr 超过容差 $tol（at index $at: exp=${expected[at]}, act=${actual[at]}）")
        println("[cross-validation] $name OK, maxErr=$maxErr (tol=$tol)")
    }
}

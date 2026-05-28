package org.shiroumi.strategy.research.tuner

import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDManager
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import org.shiroumi.strategy.research.tuner.differentiable.DifferentiableModel
import org.shiroumi.strategy.research.tuner.differentiable.GradientDescentOptimizer
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradientDescentOptimizerTest {

    /**
     * 线性回归参数恢复：
     *   y = 2.0 * x + 1.0  → 期望 w 收敛到 2.0，b 收敛到 1.0
     */
    @Test
    fun `should recover linear regression parameters`() {
        val ctx = newTestContext()
        val xs = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val trueW = 2.0f
        val trueB = 1.0f
        val ys = FloatArray(xs.size) { trueW * xs[it] + trueB }

        val model = object : DifferentiableModel {
            lateinit var w: NDArray
            lateinit var b: NDArray
            private lateinit var xT: NDArray
            private lateinit var yT: NDArray

            override fun init(manager: NDManager): List<DifferentiableModel.Parameter> {
                w = manager.create(0.0f).also { it.setRequiresGradient(true) }
                b = manager.create(0.0f).also { it.setRequiresGradient(true) }
                xT = manager.create(xs)
                yT = manager.create(ys)
                return listOf(
                    DifferentiableModel.Parameter("w", w),
                    DifferentiableModel.Parameter("b", b),
                )
            }

            override fun loss(manager: NDManager): NDArray {
                val pred = xT.mul(w).add(b)
                val diff = pred.sub(yT)
                return diff.mul(diff).mean()
            }
        }

        val optimizer = GradientDescentOptimizer(
            model = model,
            budget = TuningBudget(maxIter = 500, patience = 0, minDelta = 1e-9),
            kind = GradientDescentOptimizer.Kind.ADAM,
            learningRate = 0.1f,
        )

        val result = optimizer.optimize(ctx)
        val wFinal = result.best.params.getValue("w").toFloat()
        val bFinal = result.best.params.getValue("b").toFloat()
        assertTrue(abs(wFinal - trueW) < 0.05f, "w should approach $trueW, got $wFinal")
        assertTrue(abs(bFinal - trueB) < 0.05f, "b should approach $trueB, got $bFinal")
    }

    /**
     * 周期函数参数恢复（核心场景：未来情绪周期函数拟合）。
     *   y = A·sin(ω·t + φ)，真值 A=1.5, ω=0.3, φ=0.4
     *   优化器从近真值起点 (A=1.0, ω=0.28, φ=0.0) 出发，应恢复出真参数。
     *
     * 备注：sin 函数对频率参数是强非凸的（相位漂移很容易让梯度走错方向），
     * 因此实际项目中拟合情绪周期函数必然需要先验初值（如 FFT 主频）；
     * 这里测试也用接近真值的初值，模拟"先验初值 + 局部精修"的工程场景。
     */
    @Test
    fun `should fit single-frequency sin wave`() {
        val ctx = newTestContext()
        val n = 200
        val trueA = 1.5f
        val trueOmega = 0.3f
        val truePhi = 0.4f
        val ts = FloatArray(n) { it * 0.1f }
        val ys = FloatArray(n) { sin(trueOmega.toDouble() * ts[it] + truePhi.toDouble()).toFloat() * trueA }

        val model = object : DifferentiableModel {
            lateinit var a: NDArray
            lateinit var omega: NDArray
            lateinit var phi: NDArray
            private lateinit var tT: NDArray
            private lateinit var yT: NDArray

            override fun init(manager: NDManager): List<DifferentiableModel.Parameter> {
                a = manager.create(1.0f).also { it.setRequiresGradient(true) }
                omega = manager.create(0.28f).also { it.setRequiresGradient(true) }
                phi = manager.create(0.0f).also { it.setRequiresGradient(true) }
                tT = manager.create(ts)
                yT = manager.create(ys)
                return listOf(
                    DifferentiableModel.Parameter("A", a),
                    DifferentiableModel.Parameter("omega", omega),
                    DifferentiableModel.Parameter("phi", phi),
                )
            }

            override fun loss(manager: NDManager): NDArray {
                val pred = tT.mul(omega).add(phi).sin().mul(a)
                val diff = pred.sub(yT)
                return diff.mul(diff).mean()
            }
        }

        val optimizer = GradientDescentOptimizer(
            model = model,
            budget = TuningBudget(maxIter = 5000, patience = 0, minDelta = 1e-12),
            kind = GradientDescentOptimizer.Kind.ADAM,
            learningRate = 0.02f,
        )

        val result = optimizer.optimize(ctx)
        val aFinal = result.best.params.getValue("A").toFloat()
        val omegaFinal = result.best.params.getValue("omega").toFloat()
        val finalLoss = -result.best.observation.score

        // sin 拟合允许等价解 (A, ω, φ) ↔ (-A, ω, φ+π)，因此用 |A| 判定
        assertTrue(abs(abs(aFinal) - trueA) < 0.1f, "|A| should approach $trueA, got $aFinal")
        assertTrue(abs(omegaFinal - trueOmega) < 0.02f, "omega should approach $trueOmega, got $omegaFinal")
        assertTrue(finalLoss < 0.05, "final MSE loss should be tiny, got $finalLoss")
    }

    @Test
    fun `should write result to workspace`() {
        val ctx = newTestContext()
        val model = object : DifferentiableModel {
            lateinit var x: NDArray
            override fun init(manager: NDManager): List<DifferentiableModel.Parameter> {
                x = manager.create(5.0f).also { it.setRequiresGradient(true) }
                return listOf(DifferentiableModel.Parameter("x", x))
            }
            override fun loss(manager: NDManager): NDArray = x.mul(x) // min at x=0
        }
        val optimizer = GradientDescentOptimizer(
            model = model,
            budget = TuningBudget(maxIter = 50, patience = 0),
            kind = GradientDescentOptimizer.Kind.SGD,
            learningRate = 0.1f,
        )
        val result = optimizer.optimize(ctx)
        val dir = result.writeTo(ctx)
        assertTrue(Files.exists(dir.resolve("result.json")))
        assertTrue(Files.exists(dir.resolve("trace.csv")))
        assertNotNull(result.best)
        val xFinal = result.best.params.getValue("x").toFloat()
        assertTrue(abs(xFinal) < 0.1f, "x should approach 0, got $xFinal")
    }
}

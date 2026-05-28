package org.shiroumi.strategy.research.tuner

import org.junit.jupiter.api.Test
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousDim
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousSpace
import org.shiroumi.strategy.research.tuner.blackbox.NelderMeadOptimizer
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.StopReason
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NelderMeadOptimizerTest {

    /** 二维球面函数：score = -(x-3)² - (y+2)²，最大点 (3, -2)，最大值 0。 */
    @Test
    fun `should converge to global max of 2D sphere`() {
        val ctx = newTestContext()
        val space = ContinuousSpace(
            listOf(
                ContinuousDim("x", lower = -10.0, upper = 10.0, initial = 0.0),
                ContinuousDim("y", lower = -10.0, upper = 10.0, initial = 0.0),
            )
        )
        val optimizer = NelderMeadOptimizer(
            space = space,
            objective = { _, params ->
                val x = params.getValue("x").toDouble()
                val y = params.getValue("y").toDouble()
                Observation(score = -((x - 3.0) * (x - 3.0) + (y + 2.0) * (y + 2.0)))
            },
            budget = TuningBudget(maxIter = 200, patience = 0, minDelta = 0.0),
            initialStep = 0.2,
        )

        val result = optimizer.optimize(ctx)
        val bestX = result.best.params.getValue("x").toDouble()
        val bestY = result.best.params.getValue("y").toDouble()

        assertTrue(abs(bestX - 3.0) < 1e-3, "x should converge to 3.0, got $bestX")
        assertTrue(abs(bestY - (-2.0)) < 1e-3, "y should converge to -2.0, got $bestY")
        assertTrue(result.best.observation.score > -1e-4, "score should approach 0, got ${result.best.observation.score}")
    }

    /** 单维带边界的二次函数：最优点 (5)，初值在边界附近；验证 clamp 不破坏搜索。 */
    @Test
    fun `should respect bounds`() {
        val ctx = newTestContext()
        val space = ContinuousSpace(
            listOf(ContinuousDim("x", lower = 0.0, upper = 10.0, initial = 1.0))
        )
        var maxObserved = Double.NEGATIVE_INFINITY
        var minObserved = Double.POSITIVE_INFINITY
        val optimizer = NelderMeadOptimizer(
            space = space,
            objective = { _, params ->
                val x = params.getValue("x").toDouble()
                maxObserved = maxOf(maxObserved, x)
                minObserved = minOf(minObserved, x)
                Observation(score = -((x - 5.0) * (x - 5.0)))
            },
            budget = TuningBudget(maxIter = 300, patience = 0, minDelta = 0.0),
            initialStep = 0.5,
        )

        val result = optimizer.optimize(ctx)
        val bestX = result.best.params.getValue("x").toDouble()

        // 所有访问过的点都不能越界（验证 clamp）
        assertTrue(maxObserved <= 10.0 && minObserved >= 0.0, "out-of-bounds visit: [$minObserved, $maxObserved]")
        // 最优点附近（NM 在单维退化为线搜索，给较宽松阈值）
        assertTrue(bestX in 0.0..10.0, "best x should stay in bounds, got $bestX")
        assertTrue(abs(bestX - 5.0) < 0.1, "x should converge near 5.0, got $bestX")
    }

    /** Rosenbrock 函数（取负，转最大化）：经典非凸优化基准。 */
    @Test
    fun `should converge on rosenbrock`() {
        val ctx = newTestContext()
        val space = ContinuousSpace(
            listOf(
                ContinuousDim("x", lower = -2.0, upper = 2.0, initial = -1.2),
                ContinuousDim("y", lower = -2.0, upper = 2.0, initial = 1.0),
            )
        )
        val optimizer = NelderMeadOptimizer(
            space = space,
            objective = { _, params ->
                val x = params.getValue("x").toDouble()
                val y = params.getValue("y").toDouble()
                val a = 1.0 - x
                val b = y - x * x
                Observation(score = -(a * a + 100.0 * b * b))
            },
            budget = TuningBudget(maxIter = 500, patience = 0, minDelta = 0.0),
            initialStep = 0.1,
        )

        val result = optimizer.optimize(ctx)
        val bestX = result.best.params.getValue("x").toDouble()
        val bestY = result.best.params.getValue("y").toDouble()

        // Rosenbrock 最优点 (1, 1)，得分 0；NM 收敛较慢，放宽到 1e-2
        assertTrue(abs(bestX - 1.0) < 5e-2, "x should approach 1.0, got $bestX")
        assertTrue(abs(bestY - 1.0) < 5e-2, "y should approach 1.0, got $bestY")
    }

    @Test
    fun `should write result and trace to workspace`() {
        val ctx = newTestContext()
        val space = ContinuousSpace(
            listOf(ContinuousDim("x", lower = -1.0, upper = 1.0, initial = 0.0))
        )
        val optimizer = NelderMeadOptimizer(
            space = space,
            objective = { _, params -> Observation(score = -params.getValue("x").toDouble().let { it * it }) },
            budget = TuningBudget(maxIter = 15, patience = 0),
        )

        val result = optimizer.optimize(ctx)
        val dir = result.writeTo(ctx)
        assertTrue(Files.exists(dir.resolve("result.json")), "result.json missing")
        assertTrue(Files.exists(dir.resolve("trace.csv")), "trace.csv missing")
        val csvHead = Files.readAllLines(dir.resolve("trace.csv")).first()
        assertTrue(csvHead.startsWith("iter,score,qualified,took_ms"), "csv header malformed: $csvHead")
        assertNotNull(result.best)
    }

    @Test
    fun `should early stop when no improvement`() {
        val ctx = newTestContext()
        val space = ContinuousSpace(
            listOf(ContinuousDim("x", lower = -10.0, upper = 10.0, initial = 0.0))
        )
        var evalCount = 0
        val optimizer = NelderMeadOptimizer(
            space = space,
            objective = { _, _ ->
                evalCount++
                // 恒等：所有点得分相同 → 没有改善 → 触发早停
                Observation(score = 1.0)
            },
            budget = TuningBudget(maxIter = 1000, patience = 3, minDelta = 1e-9),
        )
        val result = optimizer.optimize(ctx)
        assertTrue(result.trace.size < 1000, "should not exhaust full budget; got ${result.trace.size}")
        assertTrue(
            result.stopReason == StopReason.EARLY_STOP_NO_IMPROVE ||
                result.stopReason == StopReason.OPTIMIZER_CONVERGED,
            "expected EARLY_STOP_NO_IMPROVE or OPTIMIZER_CONVERGED, got ${result.stopReason}",
        )
        assertEquals(evalCount, result.trace.size)
    }
}

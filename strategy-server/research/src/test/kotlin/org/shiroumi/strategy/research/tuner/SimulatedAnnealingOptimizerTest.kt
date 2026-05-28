package org.shiroumi.strategy.research.tuner

import org.junit.jupiter.api.Test
import org.shiroumi.strategy.research.tuner.blackbox.DiscreteDim
import org.shiroumi.strategy.research.tuner.blackbox.DiscreteSpace
import org.shiroumi.strategy.research.tuner.blackbox.SimulatedAnnealingOptimizer
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedAnnealingOptimizerTest {

    /**
     * 双峰函数：在 x=20 有局部峰（高 1.0），x=80 有全局峰（高 2.0）。
     * 从 x=20 出发，Hill Climbing 会卡在局部最优；SA 应该有机会跳出来。
     */
    @Test
    fun `should escape local optimum`() {
        val ctx = newTestContext()
        val choices = (0..100 step 5).map { it.toString() }
        val space = DiscreteSpace(
            listOf(DiscreteDim("x", choices, initial = "20"))
        )

        fun score(x: Int): Double {
            val localPeak = 1.0 * kotlin.math.exp(-((x - 20) * (x - 20)) / 50.0)
            val globalPeak = 2.0 * kotlin.math.exp(-((x - 80) * (x - 80)) / 50.0)
            return localPeak + globalPeak
        }

        val optimizer = SimulatedAnnealingOptimizer(
            space = space,
            objective = { _, params -> Observation(score = score(params.getValue("x").toInt())) },
            budget = TuningBudget(maxIter = 400, patience = 0),
            initialTemperature = 0.5,
            coolingRate = 0.97,
            minTemperature = 1e-3,
            random = Random(123L),
        )
        val result = optimizer.optimize(ctx)
        val bestX = result.best.params.getValue("x").toInt()
        // 应该找到 x=80 附近（全局峰）
        assertTrue(abs(bestX - 80) <= 10, "should find global optimum near 80, got $bestX")
        assertTrue(result.best.observation.score > 1.5, "score should be near global peak 2.0, got ${result.best.observation.score}")
    }

    @Test
    fun `should still work on unimodal`() {
        val ctx = newTestContext()
        val space = DiscreteSpace(
            listOf(DiscreteDim("k", listOf("a", "b", "c", "d"), initial = "a"))
        )
        val optimizer = SimulatedAnnealingOptimizer(
            space = space,
            objective = { _, params ->
                Observation(score = when (params.getValue("k")) { "a" -> 0.0; "b" -> 1.0; "c" -> 2.0; "d" -> 3.0; else -> -1.0 })
            },
            budget = TuningBudget(maxIter = 100, patience = 0),
            initialTemperature = 0.5,
            coolingRate = 0.9,
            random = Random(0L),
        )
        val result = optimizer.optimize(ctx)
        assertEquals("d", result.best.params.getValue("k"))
    }
}

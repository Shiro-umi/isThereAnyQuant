package org.shiroumi.strategy.research.tuner

import org.junit.jupiter.api.Test
import org.shiroumi.strategy.research.tuner.blackbox.DiscreteDim
import org.shiroumi.strategy.research.tuner.blackbox.DiscreteSpace
import org.shiroumi.strategy.research.tuner.blackbox.HillClimbingOptimizer
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HillClimbingOptimizerTest {

    /**
     * 离散二维：window ∈ {20, 30, 40, 50, 60}, mode ∈ {hamming, hanning, blackman}
     * 真实最优：window=40, mode=hanning (人为构造峰值)
     */
    @Test
    fun `should find global optimum on unimodal discrete space`() {
        val ctx = newTestContext()
        val space = DiscreteSpace(
            listOf(
                DiscreteDim("window", listOf("20", "30", "40", "50", "60"), initial = "20"),
                DiscreteDim("mode", listOf("hamming", "hanning", "blackman"), initial = "hamming"),
            )
        )
        // 单峰：window=40 时得分最高，mode=hanning 时得分最高；两者独立
        fun windowScore(w: Int): Double = -((w - 40).toDouble() * (w - 40)) / 100.0
        fun modeScore(m: String): Double = when (m) { "hanning" -> 1.0; "hamming" -> 0.5; else -> 0.0 }
        val optimizer = HillClimbingOptimizer(
            space = space,
            objective = { _, params ->
                val w = params.getValue("window").toInt()
                val m = params.getValue("mode")
                Observation(score = windowScore(w) + modeScore(m))
            },
            budget = TuningBudget(maxIter = 100, patience = 0, minDelta = 1e-9),
            strategy = HillClimbingOptimizer.NeighborStrategy.STEEPEST,
        )

        val result = optimizer.optimize(ctx)
        assertEquals("40", result.best.params.getValue("window"))
        assertEquals("hanning", result.best.params.getValue("mode"))
    }

    @Test
    fun `first improvement strategy should also work`() {
        val ctx = newTestContext()
        val space = DiscreteSpace(
            listOf(
                DiscreteDim("a", listOf("0", "1", "2", "3"), initial = "0"),
                DiscreteDim("b", listOf("0", "1", "2", "3"), initial = "0"),
            )
        )
        val optimizer = HillClimbingOptimizer(
            space = space,
            objective = { _, params ->
                val a = params.getValue("a").toInt()
                val b = params.getValue("b").toInt()
                Observation(score = a.toDouble() + b.toDouble()) // 单调，最优 (3,3)
            },
            budget = TuningBudget(maxIter = 100, patience = 0, minDelta = 1e-9),
            strategy = HillClimbingOptimizer.NeighborStrategy.FIRST_IMPROVEMENT,
        )

        val result = optimizer.optimize(ctx)
        assertEquals("3", result.best.params.getValue("a"))
        assertEquals("3", result.best.params.getValue("b"))
    }

    /** 没有更优邻居时立即收敛，不会跑满预算。 */
    @Test
    fun `should converge fast when starting at optimum`() {
        val ctx = newTestContext()
        val space = DiscreteSpace(
            listOf(DiscreteDim("k", listOf("a", "b", "c"), initial = "a"))
        )
        val optimizer = HillClimbingOptimizer(
            space = space,
            objective = { _, params ->
                Observation(score = if (params.getValue("k") == "a") 10.0 else 0.0)
            },
            budget = TuningBudget(maxIter = 100, patience = 0),
        )
        val result = optimizer.optimize(ctx)
        assertEquals("a", result.best.params.getValue("k"))
        assertTrue(result.trace.size <= 3, "should converge fast, trace=${result.trace.size}")
    }
}

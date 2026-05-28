package org.shiroumi.strategy.research.tuner.blackbox

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Simulated Annealing（模拟退火，最大化语义）。
 *
 * 在 [HillClimbingOptimizer] 的基础上引入"温度"机制：以概率 `exp(Δ / T)` 接受**劣化**邻居，
 * 跳出局部最优；温度随迭代按 [coolingRate] 几何衰减，最终退化为爬山法。
 *
 * 适用于离散参数空间出现局部最优、Hill Climbing 早早卡住的场景。
 *
 * 算法步骤（每次迭代）：
 * 1. 从当前位置随机选一个维度，随机切换到另一个不同的 choice，得到邻居
 * 2. ΔS = neighbor.score - current.score
 * 3. 若 Δ > 0 直接接受；否则以 `exp(Δ / T)` 概率接受
 * 4. T ← T × coolingRate
 *
 * @property initialTemperature 初始温度；建议略大于"最初几轮观测到的得分波动幅度"
 * @property coolingRate         温度衰减系数（典型 0.85 ~ 0.99）
 * @property minTemperature      温度下限；降到此值后退化为纯爬山
 */
class SimulatedAnnealingOptimizer(
    space: DiscreteSpace,
    objective: ObjectiveFunction,
    budget: TuningBudget,
    private val initialTemperature: Double = 1.0,
    private val coolingRate: Double = 0.95,
    private val minTemperature: Double = 1e-4,
    private val random: Random = Random(0L),
) : BlackBoxOptimizer<DiscreteSpace>(space, objective, budget) {

    override val name: String = "simulated-annealing"

    init {
        require(initialTemperature > 0.0) { "initialTemperature 必须 > 0：$initialTemperature" }
        require(coolingRate in 0.0..1.0) { "coolingRate 必须 ∈ (0, 1]：$coolingRate" }
        require(minTemperature > 0.0 && minTemperature < initialTemperature) {
            "minTemperature 必须 ∈ (0, initialTemperature)：$minTemperature"
        }
    }

    override fun doOptimize(ctx: ResearchContext) {
        var temperature = initialTemperature
        var current = space.initialIndices.copyOf()
        var currentObs = scoreOf(ctx, space.encodeIndices(current)) ?: return

        while (!stop()) {
            val neighbor = randomNeighbor(current) ?: run {
                markConverged()
                return
            }
            val obs = scoreOf(ctx, space.encodeIndices(neighbor)) ?: return
            val delta = obs.score - currentObs.score

            val accept = if (delta > 0.0) true
            else {
                val p = exp(delta / max(temperature, 1e-12))
                random.nextDouble() < p
            }

            if (accept) {
                current = neighbor
                currentObs = obs
            }
            temperature = max(temperature * coolingRate, minTemperature)
        }
    }

    private fun randomNeighbor(current: IntArray): IntArray? {
        val viableDims = space.dims.withIndex().filter { (_, d) -> d.choices.size > 1 }.map { it.index }
        if (viableDims.isEmpty()) return null
        val dimIdx = viableDims.random(random)
        val choices = space.dims[dimIdx].choices
        var newChoiceIdx: Int
        do {
            newChoiceIdx = random.nextInt(choices.size)
        } while (newChoiceIdx == current[dimIdx])
        return current.copyOf().also { it[dimIdx] = newChoiceIdx }
    }
}

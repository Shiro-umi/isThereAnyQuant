package org.shiroumi.strategy.research.tuner.blackbox

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import kotlin.random.Random

/**
 * Hill Climbing（爬山法，最大化语义）。
 *
 * 离散参数调优的最基础方法：每轮从当前位置出发，枚举 / 抽样邻居，跳到第一个比当前更优的邻居。
 * 简单可解释，适合：
 * - 维度低、每维 choices 少（邻居总数可枚举）的离散空间
 * - 想要可调试、可读 trace 的调优过程
 *
 * 局部最优陷阱：若发现轨迹早早卡住，应改用 [SimulatedAnnealingOptimizer]。
 *
 * @property strategy 邻居选择策略：
 *   - [NeighborStrategy.FIRST_IMPROVEMENT]：扫到第一个改进就跳（默认，预算友好）
 *   - [NeighborStrategy.STEEPEST]：扫完所有邻居，跳最好的（更稳但每轮预算消耗大）
 * @property randomRestart 局部最优时是否随机重启；true 则在卡住后跳到随机点继续搜索
 */
class HillClimbingOptimizer(
    space: DiscreteSpace,
    objective: ObjectiveFunction,
    budget: TuningBudget,
    private val strategy: NeighborStrategy = NeighborStrategy.FIRST_IMPROVEMENT,
    private val randomRestart: Boolean = false,
    private val random: Random = Random(0L),
) : BlackBoxOptimizer<DiscreteSpace>(space, objective, budget) {

    override val name: String = "hill-climbing"

    enum class NeighborStrategy { FIRST_IMPROVEMENT, STEEPEST }

    override fun doOptimize(ctx: ResearchContext) {
        var current = space.initialIndices.copyOf()
        var currentObs = evaluateAt(ctx, current) ?: return

        while (!stop()) {
            val moved = when (strategy) {
                NeighborStrategy.FIRST_IMPROVEMENT -> firstImprovement(ctx, current, currentObs)
                NeighborStrategy.STEEPEST -> steepest(ctx, current, currentObs)
            } ?: run {
                // 没有更优邻居 → 卡在局部最优
                if (randomRestart && !stop()) {
                    val randomIndices = IntArray(space.dims.size) { random.nextInt(space.dims[it].choices.size) }
                    val obs = evaluateAt(ctx, randomIndices) ?: return
                    current = randomIndices
                    currentObs = obs
                    return@run Pair(current, currentObs)
                }
                markConverged()
                null
            } ?: return

            current = moved.first
            currentObs = moved.second
        }
    }

    /** 扫描邻居，遇到第一个改进即跳过去；找不到返回 null。 */
    private fun firstImprovement(
        ctx: ResearchContext,
        current: IntArray,
        currentObs: Observation,
    ): Pair<IntArray, Observation>? {
        for ((dimIdx, dim) in space.dims.withIndex()) {
            for (choiceIdx in dim.choices.indices) {
                if (choiceIdx == current[dimIdx]) continue
                if (stop()) return null
                val candidate = current.copyOf().also { it[dimIdx] = choiceIdx }
                val obs = evaluateAt(ctx, candidate) ?: return null
                if (obs.score > currentObs.score + budget.minDelta) {
                    return candidate to obs
                }
            }
        }
        return null
    }

    /** 扫描全部邻居，返回最好的（且必须比 current 更好）；找不到返回 null。 */
    private fun steepest(
        ctx: ResearchContext,
        current: IntArray,
        currentObs: Observation,
    ): Pair<IntArray, Observation>? {
        var bestIndices: IntArray? = null
        var bestObs: Observation? = null
        for ((dimIdx, dim) in space.dims.withIndex()) {
            for (choiceIdx in dim.choices.indices) {
                if (choiceIdx == current[dimIdx]) continue
                if (stop()) return bestIndices?.let { it to bestObs!! }
                val candidate = current.copyOf().also { it[dimIdx] = choiceIdx }
                val obs = evaluateAt(ctx, candidate) ?: return bestIndices?.let { it to bestObs!! }
                if (bestObs == null || obs.score > bestObs.score) {
                    bestObs = obs
                    bestIndices = candidate
                }
            }
        }
        return if (bestObs != null && bestObs.score > currentObs.score + budget.minDelta) {
            bestIndices!! to bestObs
        } else null
    }

    private fun evaluateAt(ctx: ResearchContext, indices: IntArray): Observation? =
        scoreOf(ctx, space.encodeIndices(indices))
}

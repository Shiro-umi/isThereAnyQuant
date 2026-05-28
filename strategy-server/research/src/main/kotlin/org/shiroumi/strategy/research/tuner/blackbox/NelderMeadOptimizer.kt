package org.shiroumi.strategy.research.tuner.blackbox

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget

/**
 * Nelder-Mead 单纯形法（最大化语义）。
 *
 * 教科书算法（Nelder & Mead, 1965）：
 * - 在 N 维空间维护 N+1 个顶点，每次迭代计算反射 / 扩展 / 收缩 / 整体收缩
 * - 无需梯度，适合低维（≤10）、无噪声或弱噪声的连续目标
 * - 对噪声大的目标建议改用 [SpsaOptimizer]
 *
 * 标准参数： alpha=1.0（反射）, gamma=2.0（扩展）, rho=0.5（收缩）, sigma=0.5（整体收缩）。
 *
 * @property initialStep 初始单纯形的"步长"占维度宽度的比例（默认 0.1，即每维 10% 范围）。
 *                       太小容易陷局部极值；太大初期探索浪费预算。
 */
class NelderMeadOptimizer(
    space: ContinuousSpace,
    objective: ObjectiveFunction,
    budget: TuningBudget,
    private val initialStep: Double = 0.1,
    private val alpha: Double = 1.0,
    private val gamma: Double = 2.0,
    private val rho: Double = 0.5,
    private val sigma: Double = 0.5,
    private val tolerance: Double = 1e-8,
) : BlackBoxOptimizer<ContinuousSpace>(space, objective, budget) {

    override val name: String = "nelder-mead"

    init {
        require(initialStep > 0.0 && initialStep <= 1.0) { "initialStep 必须 ∈ (0, 1]：$initialStep" }
        require(alpha > 0.0 && gamma > 1.0 && rho > 0.0 && rho < 1.0 && sigma > 0.0 && sigma < 1.0) {
            "Nelder-Mead 系数非法：alpha=$alpha gamma=$gamma rho=$rho sigma=$sigma"
        }
    }

    override fun doOptimize(ctx: ResearchContext) {
        val n = space.dims.size

        // 初始单纯形：以 initial 为基点，每个维度沿正方向偏移 initialStep * width 得到一个新顶点
        val widths = space.widths
        val simplex = mutableListOf<Vertex>()
        simplex += vertex(ctx, space.initialVector) ?: return
        for (i in 0 until n) {
            if (stop()) return
            val v = space.initialVector.copyOf()
            val step = widths[i] * initialStep
            // 若 initial 已贴近上界，则向下偏移
            val newVal = if (v[i] + step <= space.dims[i].upper) v[i] + step else v[i] - step
            v[i] = newVal.coerceIn(space.dims[i].lower, space.dims[i].upper)
            simplex += vertex(ctx, v) ?: return
        }

        // 主循环
        while (!stop()) {
            // 按得分降序（最大化）排序：simplex[0] 最好，simplex[n] 最差
            simplex.sortByDescending { it.score }

            // 单纯形坍缩到给定容忍度 → 视为收敛
            val spread = simplex.first().score - simplex.last().score
            if (spread < tolerance) {
                markConverged()
                return
            }

            // 计算 centroid（去掉最差顶点后的均值）
            val centroid = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0
                for (k in 0 until n) s += simplex[k].point[i]
                centroid[i] = s / n
            }

            val worst = simplex[n]

            // 反射点 xr = centroid + alpha * (centroid - worst)
            val xr = DoubleArray(n) { i -> centroid[i] + alpha * (centroid[i] - worst.point[i]) }
            val vr = vertex(ctx, xr) ?: return
            if (stop()) return

            if (vr.score >= simplex[n - 1].score && vr.score < simplex[0].score) {
                // 反射点比次差好、不比最好好 → 接受反射
                simplex[n] = vr
                continue
            }

            if (vr.score >= simplex[0].score) {
                // 反射点比最好还好 → 尝试扩展 xe = centroid + gamma * (xr - centroid)
                val xe = DoubleArray(n) { i -> centroid[i] + gamma * (xr[i] - centroid[i]) }
                val ve = vertex(ctx, xe) ?: return
                simplex[n] = if (ve.score > vr.score) ve else vr
                continue
            }

            // 反射点连次差都不如 → 尝试收缩
            // 外收缩 / 内收缩取决于 vr 与 worst 的关系
            val xc = if (vr.score > worst.score) {
                // 外收缩：xc = centroid + rho * (xr - centroid)
                DoubleArray(n) { i -> centroid[i] + rho * (xr[i] - centroid[i]) }
            } else {
                // 内收缩：xc = centroid - rho * (centroid - worst)
                DoubleArray(n) { i -> centroid[i] - rho * (centroid[i] - worst.point[i]) }
            }
            val vc = vertex(ctx, xc) ?: return
            if (stop()) return
            if (vc.score > worst.score) {
                simplex[n] = vc
                continue
            }

            // 收缩失败 → 整体收缩：所有顶点向最佳点 sigma 收拢
            val bestPoint = simplex[0].point
            for (i in 1..n) {
                if (stop()) return
                val xs = DoubleArray(n) { j -> bestPoint[j] + sigma * (simplex[i].point[j] - bestPoint[j]) }
                simplex[i] = vertex(ctx, xs) ?: return
            }
        }
    }

    private fun vertex(ctx: ResearchContext, point: DoubleArray): Vertex? {
        val clamped = space.clamp(point)
        val params = space.encode(clamped)
        val obs = scoreOf(ctx, params) ?: return null
        return Vertex(clamped, obs)
    }

    /** 单纯形顶点：点 + 观测。 */
    private data class Vertex(val point: DoubleArray, val observation: Observation) {
        val score: Double get() = observation.score

        override fun equals(other: Any?): Boolean =
            other is Vertex && point.contentEquals(other.point) && observation == other.observation

        override fun hashCode(): Int = 31 * point.contentHashCode() + observation.hashCode()
    }
}

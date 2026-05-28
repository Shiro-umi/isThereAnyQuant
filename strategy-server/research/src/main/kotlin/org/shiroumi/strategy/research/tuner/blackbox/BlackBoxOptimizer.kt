package org.shiroumi.strategy.research.tuner.blackbox

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.Optimizer
import org.shiroumi.strategy.research.tuner.core.StopReason
import org.shiroumi.strategy.research.tuner.core.TrialRecord
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import org.shiroumi.strategy.research.tuner.core.TuningResult

/**
 * 黑盒优化器抽象基类。
 *
 * 共享行为：
 * - 调用 [ObjectiveFunction]，把"参数 → 观测"封装成最大化语义
 * - 维护轨迹 [trace] 与最优记录 [best]
 * - 统一处理预算（[TuningBudget.maxIter]）、早停（[TuningBudget.patience]）、目标命中
 * - 结果落盘走 [TuningResult.writeTo]
 *
 * 子类只需要实现两件事：
 * 1. 内部迭代逻辑（在 [doOptimize] 中调 [scoreOf] 取得观测）
 * 2. 终止判据（通过 [stop] 提前终止）
 *
 * **最大化语义**：返回的 score 越大越好；内部子类如果用最小化算法（如 Nelder-Mead 内部
 * 走最小化），请在传入算法时取负号，并在转换回 [Observation] 时再取负。
 */
abstract class BlackBoxOptimizer<S : SearchSpace>(
    protected val space: S,
    protected val objective: ObjectiveFunction,
    override val budget: TuningBudget,
) : Optimizer {

    private val _trace = mutableListOf<TrialRecord>()
    /** 当前轨迹（已观测的全部试探，按迭代顺序）。 */
    protected val trace: List<TrialRecord> get() = _trace

    /** 当前最优记录；null 表示一次都还没观测到。 */
    protected var best: TrialRecord? = null
        private set

    private var stopReason: StopReason = StopReason.BUDGET_EXHAUSTED
    private var noImproveCount: Int = 0
    private var converged: Boolean = false

    init {
        space.validate()
    }

    /**
     * 执行优化。
     *
     * 子类的迭代循环只调 [scoreOf] 取得观测、调 [stop] 提前终止；不要直接动 [_trace] / [best]。
     */
    fun optimize(ctx: ResearchContext): TuningResult {
        doOptimize(ctx)
        val finalBest = best ?: error("$name 没有产生任何试探记录，无法返回最优结果")
        val result = TuningResult(
            best = finalBest,
            trace = _trace.toList(),
            stopReason = stopReason,
            optimizerName = name,
        )
        return result
    }

    /**
     * 评估一组候选参数。
     *
     * - 自动派生新 [ResearchContext]（params 合并覆盖）
     * - 自动记录试探到轨迹
     * - 自动维护 [best] 与早停计数
     * - 返回当前观测，方便子类决定下一个候选
     *
     * 超出预算或命中目标 / 触发早停后返回 null，子类应立即退出迭代循环。
     */
    protected fun scoreOf(ctx: ResearchContext, params: Map<String, String>): Observation? {
        if (_trace.size >= budget.maxIter) {
            stopReason = StopReason.BUDGET_EXHAUSTED
            return null
        }

        val merged = ctx.params + params
        val derived = ctx.copy(params = merged)
        val t0 = System.currentTimeMillis()
        val obs = runCatching { objective.evaluate(derived, params) }
            .getOrElse { err ->
                Observation(
                    score = Double.NEGATIVE_INFINITY,
                    qualified = false,
                    detail = "评估异常: ${err.javaClass.simpleName}: ${err.message}",
                )
            }
        val took = System.currentTimeMillis() - t0

        val record = TrialRecord(
            iter = _trace.size,
            params = params,
            observation = obs,
            tookMillis = took,
        )
        _trace += record

        val currentBest = best
        if (currentBest == null || obs.score > currentBest.observation.score + budget.minDelta) {
            best = record
            noImproveCount = 0
        } else {
            noImproveCount++
        }

        // 命中目标分数
        budget.targetScore?.let { target ->
            if (obs.score >= target) {
                stopReason = StopReason.TARGET_SCORE_REACHED
            }
        }
        // 早停
        if (budget.patience > 0 && noImproveCount >= budget.patience) {
            stopReason = StopReason.EARLY_STOP_NO_IMPROVE
        }

        return obs
    }

    /** 子类显式声明已收敛（如 Nelder-Mead 单纯形坍缩、Hill Climbing 无更优邻居）。 */
    protected fun markConverged() {
        converged = true
        stopReason = StopReason.OPTIMIZER_CONVERGED
    }

    /** 是否需要中止迭代（被预算耗尽、早停、目标命中、子类收敛任一触发）。 */
    protected fun stop(): Boolean =
        converged ||
            _trace.size >= budget.maxIter ||
            stopReason == StopReason.TARGET_SCORE_REACHED ||
            stopReason == StopReason.EARLY_STOP_NO_IMPROVE

    /** 子类实现：内部迭代逻辑。 */
    protected abstract fun doOptimize(ctx: ResearchContext)
}

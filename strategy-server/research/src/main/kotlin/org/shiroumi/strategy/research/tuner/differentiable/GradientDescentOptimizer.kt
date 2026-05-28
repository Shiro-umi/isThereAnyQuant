package org.shiroumi.strategy.research.tuner.differentiable

import ai.djl.engine.Engine
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDManager
import ai.djl.training.optimizer.Optimizer
import ai.djl.training.tracker.Tracker
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.Optimizer as TunerOptimizer
import org.shiroumi.strategy.research.tuner.core.StopReason
import org.shiroumi.strategy.research.tuner.core.TrialRecord
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import org.shiroumi.strategy.research.tuner.core.TuningResult
import kotlin.math.abs

/**
 * 基于 DJL 的可微优化器（最小化语义，对外 [Observation.score] 已取负转回最大化）。
 *
 * 适用场景：用户能写出**显式可微公式**（如情绪周期函数 `A·sin(2πft+φ)`、股票评分
 * `σ(w·factors+b)`），调优器对参数做反向梯度下降。
 *
 * 流程：
 * 1. 在 [optimize] 入口建 [NDManager]，调 [DifferentiableModel.init] 拿参数
 * 2. 主循环：开 [GradientCollector] → forward 算 loss → backward 算梯度
 *    → 用 DJL [Optimizer.update] 更新每个参数
 * 3. 每步把 (params snapshot, -loss) 落到 [TrialRecord] 轨迹
 * 4. 终止：达到 [TuningBudget.maxIter] / loss 连续 [TuningBudget.patience] 步无改善 / 命中 targetScore
 *
 * @property kind          底层算法类型（[Kind.ADAM] / [Kind.SGD]）
 * @property learningRate  学习率
 * @property beta1/beta2/epsilon  Adam 专属（SGD 时忽略）
 */
class GradientDescentOptimizer(
    private val model: DifferentiableModel,
    override val budget: TuningBudget,
    private val kind: Kind = Kind.ADAM,
    private val learningRate: Float = 1e-2f,
    private val beta1: Float = 0.9f,
    private val beta2: Float = 0.999f,
    private val epsilon: Float = 1e-8f,
) : TunerOptimizer {

    enum class Kind { ADAM, SGD }

    override val name: String = "gradient-descent-${kind.name.lowercase()}"

    /**
     * 启动可微优化。
     *
     * 在 [ctx.workspace]/tuner/{runId}/ 下落 result.json + trace.csv。
     */
    fun optimize(ctx: ResearchContext): TuningResult {
        // 顶层 manager，与所有训练态张量共享生命周期；optimize 结束统一关闭
        val engine = Engine.getInstance()
        val trace = mutableListOf<TrialRecord>()
        var stopReason = StopReason.BUDGET_EXHAUSTED
        var bestRecord: TrialRecord? = null
        var noImproveCount = 0

        engine.newBaseManager().use { manager ->
            val parameters = model.init(manager)
            require(parameters.isNotEmpty()) { "DifferentiableModel.init 必须至少返回一个参数" }
            require(parameters.map { it.name }.distinct().size == parameters.size) {
                "DifferentiableModel.Parameter.name 不能重复：${parameters.map { it.name }}"
            }

            val djlOptimizer = buildDjlOptimizer()

            for (iter in 0 until budget.maxIter) {
                val t0 = System.currentTimeMillis()

                val lossScalar = engine.newGradientCollector().use { gc ->
                    manager.newSubManager().use { stepMgr ->
                        val loss = model.loss(stepMgr)
                        require(loss.shape.dimension() == 0 || loss.size() == 1L) {
                            "loss 必须是标量；got shape=${loss.shape}"
                        }
                        gc.backward(loss)
                        loss.toFloatArray()[0].toDouble()
                    }
                }

                // 应用梯度
                for (p in parameters) {
                    val grad = p.value.gradient
                    djlOptimizer.update(p.name, p.value, grad)
                    grad.close()
                }

                val took = System.currentTimeMillis() - t0
                val paramsSnapshot = parameters.associate { it.name to snapshot(it.value) }

                // 统一转最大化语义
                val score = -lossScalar
                val record = TrialRecord(
                    iter = iter,
                    params = paramsSnapshot,
                    observation = Observation(score = score, qualified = true, detail = "loss=$lossScalar"),
                    tookMillis = took,
                )
                trace += record

                val currentBest = bestRecord
                if (currentBest == null || record.observation.score > currentBest.observation.score + budget.minDelta) {
                    bestRecord = record
                    noImproveCount = 0
                } else {
                    noImproveCount++
                }

                budget.targetScore?.let { target ->
                    if (record.observation.score >= target) {
                        stopReason = StopReason.TARGET_SCORE_REACHED
                        return@use
                    }
                }
                if (budget.patience > 0 && noImproveCount >= budget.patience) {
                    stopReason = StopReason.EARLY_STOP_NO_IMPROVE
                    return@use
                }
            }
        }

        val finalBest = bestRecord ?: error("$name 没有产生任何迭代记录")
        return TuningResult(
            best = finalBest,
            trace = trace,
            stopReason = stopReason,
            optimizerName = name,
        )
    }

    private fun buildDjlOptimizer(): Optimizer {
        val tracker = Tracker.fixed(learningRate)
        return when (kind) {
            Kind.ADAM -> Optimizer.adam()
                .optLearningRateTracker(tracker)
                .optBeta1(beta1)
                .optBeta2(beta2)
                .optEpsilon(epsilon)
                .build()

            Kind.SGD -> Optimizer.sgd()
                .setLearningRateTracker(tracker)
                .build()
        }
    }

    /** 把 NDArray 的当前值序列化到 `String`。标量直接出数；向量出 `[v1, v2, ...]`。 */
    private fun snapshot(array: NDArray): String {
        val flat = array.toFloatArray()
        return if (flat.size == 1) {
            format(flat[0].toDouble())
        } else {
            flat.joinToString(prefix = "[", postfix = "]") { format(it.toDouble()) }
        }
    }

    private fun format(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val abs = abs(value)
        return if (abs >= 1e6 || (abs > 0 && abs < 1e-4)) {
            "%.6e".format(value)
        } else {
            "%.6f".format(value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }
    }
}

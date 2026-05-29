package org.shiroumi.strategy.research.topic.factor.eval

import kotlin.math.PI

/**
 * §12.4 A 类卡片硬条件的阈值常量（执行手册 §14 默认参数表）。
 *
 * 集中成一处常量，让独立裁判器 [ResonanceEvaluator] 的每条判定都可溯源到手册数值，
 * 也便于在迭代报告里说明"动了哪个阈值"。**这是 eval（research 第二部分）的口径，不属于
 * agent 的研究迭代代码**——agent 不得改这里来放水。
 */
object EvaluationThresholds {
    const val MEAN_COHERENCE_MIN = 0.5          // §12.4-1
    const val COHERENCE_COVERAGE_MIN = 0.30     // §12.4-2
    val PHASE_STD_MAX = PI / 4                   // §12.4-3 ≈ 0.785
    const val OOS_IC_MIN = 0.05                  // §12.4-6
    const val HIT_RATE_LIFT = 0.05               // §12.4-7 hit_rate > baseline + 0.05
    const val TOP_BOTTOM_CONSISTENCY_MIN = 0.70  // §12.4-8 滚动同号占比 > 70%
    const val Q_VALUE_MAX = 0.1                  // §12.4-9
    const val SAMPLE_COUNT_MIN_SINGLE = 60       // §12.4-10 单因子
    const val SAMPLE_COUNT_MIN_PAIR = 80         // §12.4-10 因子对
    const val DELTA_IC_VS_BASE_MIN = 0.02        // §12.4-12 因子对额外
    const val BETA3_STABILITY_MIN = 0.70         // §12.4-12 因子对额外

    /** horizon → 有效 lead_days 区间（含端点），手册 §6.5。 */
    fun leadDaysRange(horizon: Int): ClosedFloatingPointRange<Double> = when (horizon) {
        1 -> 0.5..1.5
        3 -> 1.0..3.0
        5 -> 1.0..5.0
        else -> error("未定义 horizon=$horizon 的 lead_days 区间（手册 §6.5 仅定义 1/3/5）")
    }

    /** 因子对（pair_*）需要满足更高的样本门槛与额外两项。 */
    fun isPair(factorType: String): Boolean = factorType.startsWith("pair")
}

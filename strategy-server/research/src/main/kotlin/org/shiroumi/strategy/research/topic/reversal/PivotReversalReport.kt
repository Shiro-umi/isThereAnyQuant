package org.shiroumi.strategy.research.topic.reversal

/**
 * 反转研究的未裁判诊断度量产物（Study 输出 → Evaluation 裁判 → 落盘）。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §5.3（体检 B）/ §7.1（L1/L2）。
 * 只承载「诚实算出的原始度量」，是否合格由 [PivotReversalEvaluation] 按用户钉死口径判定。
 */
data class PivotReversalReport(
    val samples: Int,
    val positives: Int,
    val positiveRate: Double,
    /** 体检 B：每个 t−1 单因子滞后对 t 标签的判别力（AUC / Spearman IC），看单因子有没有领先信息量。 */
    val singleFactor: List<FactorDiag>,
    /** 融合公式 P^rev 在 train / val / test 上的判别力与最优阈值裁决。 */
    val fusion: SplitDiag,
    /** §3.6 消融：纯价基线 P^rev^(price) 的 test 判别力（与 fusion.test 比，验证量价增量）。 */
    val priceBaselineTest: SplitMetrics,
) {
    data class FactorDiag(val name: String, val auc: Double, val ic: Double)

    data class SplitMetrics(
        val coverage: Int,
        val positives: Int,
        val auc: Double,
        val tau: Double,
        val precision: Double,
        val recall: Double,
        val f1: Double,
        val balancedHit: Double,
    )

    data class SplitDiag(
        val train: SplitMetrics,
        val validation: SplitMetrics,
        val test: SplitMetrics,
    )
}

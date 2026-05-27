package org.shiroumi.strategy.research.output

import kotlinx.serialization.Serializable

/**
 * 共振研究的五维身份：`(因子, 目标Y, horizon, 频带, 状态)`。
 *
 * 一个身份唯一确定一个研究问题。[ResonanceMetric]（未裁判度量）与 [ResonanceCard]（裁判后结论）
 * 共享同一身份，避免字段在两层间重复定义、各自漂移。
 *
 * 字段名对齐执行手册 §12.2 schema（snake_case）。
 */
@Serializable
data class ResonanceIdentity(
    val factor_name: String,
    val factor_type: String,          // single / pair_diff / pair_product / pair_ratio
    val factor_i: String,
    val factor_j: String? = null,
    val target_y: String,
    val horizon: Int,
    val band: String,                 // F1a / F1b / F2a / F2b
    val state_id: String,             // trend=...,disp=...,vol=...
) {
    /** 落盘文件名（不含目录）：`{factor}__{Y}__h{horizon}__{band}__{state_id}.json`。 */
    fun fileName(): String = "${factor_name}__${target_y}__h${horizon}__${band}__${state_id}.json"
}

/**
 * 共振研究的**未裁判原始度量** —— [org.shiroumi.strategy.research.pipeline.ResearchStudy]（①）的标准产物。
 *
 * 这是研究迭代代码的**可复用产物**：只承载①诚实算出的原始度量与实验配置，
 * **不含任何裁判结论**（qualified / conclusion_level / interpretation 由②
 * [org.shiroumi.strategy.research.pipeline.ResearchEvaluation] 产生）。
 *
 * 下游若想复用研究数据，直接消费 [ResonanceMetric]——它中立、不被某套评估口径绑架。
 * ②评估时按 §12.4 的 12 项硬条件对照这些度量裁出 [ResonanceCard]。
 *
 * 字段名对齐执行手册 §12.2 schema（snake_case）。
 */
@Serializable
data class ResonanceMetric(
    val identity: ResonanceIdentity,
    // ---- 实验配置 ----
    val stft_window: Int,
    val norm_version: String,
    val state_window: Int,
    // ---- 原始度量（未裁判）----
    val rolling_corr_mean: Double? = null,
    val rolling_corr_stability: Double? = null,
    val mean_coherence: Double? = null,
    val max_coherence: Double? = null,
    val coherence_coverage: Double? = null,
    val phase_std: Double? = null,
    val lead_days_lag: Double? = null,
    val lead_days_phase: Double? = null,
    val lead_relation_stable: Boolean? = null,
    val beta: Double? = null,
    val oos_ic: Double? = null,
    val rank_ic_oos: Double? = null,
    val hit_rate: Double? = null,
    /** 方向命中率基准 = max(P(Y>0), P(Y<0))（手册 §6.5 baseline 定义）。eval 第 7 项独立复核 hit_rate > baseline+0.05 需要它。 */
    val baseline: Double? = null,
    val top_bottom_spread: Double? = null,
    /** top_bottom_spread 滚动窗口同号占比 ∈ [0,1]。eval 第 8 项「稳定为正(同号>70%)」需要它，单个 spread 值判不了稳定性。 */
    val top_bottom_spread_consistency: Double? = null,
    val delta_score_vs_base: Double? = null,
    val delta_ic_vs_base: Double? = null,
    /** 因子对 β3 同号占比（手册 §12.3）。eval 第 12 项（因子对额外）独立复核 beta3_stability>0.70 需要它。单因子留空。 */
    val beta3_stability: Double? = null,
    val p_value: Double? = null,
    val q_value: Double? = null,
    val sample_count: Int? = null,
    val filtfilt_lfilter_consistent: Boolean? = null,
    val regime: String? = null,
)

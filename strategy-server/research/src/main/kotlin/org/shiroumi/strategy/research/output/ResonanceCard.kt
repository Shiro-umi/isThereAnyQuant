package org.shiroumi.strategy.research.output

import kotlinx.serialization.Serializable

/**
 * 共振卡片 —— 一个 `(因子, 目标Y, horizon, 频带, 状态)` 五维组合的研究结论。
 *
 * 字段对齐执行手册 §12.2 / §12.3 的 JSON schema。这是研究内容（[org.shiroumi.strategy.research
 * .pipeline.ResearchStudy]）的标准产物形态，也是 autoresearch Metric 脚本
 * `count-resonance-cards.sh` 的统计源（扫描 `"qualified": true`）。
 *
 * 序列化采用 snake_case 以匹配既有 schema 与脚本的 grep 约定。
 * 这里只定义【数据形态】，不含任何判定逻辑；qualified 的 12 项硬条件（§12.4）由研究内容计算后填入。
 *
 * 命名约定（落盘文件名，见 README）：
 * `out/resonance_cards/{factor}__{Y}__h{horizon}__{band}__{state_id}.json`
 */
@Serializable
data class ResonanceCard(
    val factor_name: String,
    val factor_type: String,          // single / pair_diff / pair_product / pair_ratio
    val factor_i: String,
    val factor_j: String? = null,
    val target_y: String,
    val horizon: Int,
    val band: String,                 // F1a / F1b / F2a / F2b
    val stft_window: Int,
    val norm_version: String,
    val state_window: Int,
    val state_id: String,             // trend=...,disp=...,vol=...
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
    val top_bottom_spread: Double? = null,
    val delta_score_vs_base: Double? = null,
    val delta_ic_vs_base: Double? = null,
    val p_value: Double? = null,
    val q_value: Double? = null,
    val sample_count: Int? = null,
    val filtfilt_lfilter_consistent: Boolean? = null,
    val regime: String? = null,
    val conclusion_level: String,     // A / B / C / Reject
    val qualified: Boolean,           // 是否进 A 类，机械计数依据（§12.4）
    val interpretation: String? = null,
    val failure_states: String? = null,
) {
    /** 落盘文件名（不含目录）：`{factor}__{Y}__h{horizon}__{band}__{state_id}.json`。 */
    fun fileName(): String = "${factor_name}__${target_y}__h${horizon}__${band}__${state_id}.json"
}

package org.shiroumi.strategy.research.output

import kotlinx.serialization.Serializable

/**
 * 共振卡片 —— ②[org.shiroumi.strategy.research.pipeline.ResearchEvaluation] **裁判后**的最终结论产物。
 *
 * 一张卡片 = 一个 `(因子, 目标Y, horizon, 频带, 状态)` 五维组合的研究结论。
 * 它由 [ResonanceMetric]（①的未裁判度量）加上②的裁判字段（conclusion_level / qualified /
 * interpretation / failure_states）组装而成，见 [from]。
 *
 * 落盘序列化保持**扁平 snake_case**，对齐执行手册 §12.2 schema，并满足 autoresearch Metric 脚本
 * `count-resonance-cards.sh` 的 grep 约定（扫 `"qualified": true`）。
 *
 * 命名约定（落盘文件名）：`out/resonance_cards/{factor}__{Y}__h{horizon}__{band}__{state_id}.json`
 */
@Serializable
data class ResonanceCard(
    val factor_name: String,
    val factor_type: String,
    val factor_i: String,
    val factor_j: String? = null,
    val target_y: String,
    val horizon: Int,
    val band: String,
    val stft_window: Int,
    val norm_version: String,
    val state_window: Int,
    val state_id: String,
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
    val baseline: Double? = null,
    val top_bottom_spread: Double? = null,
    val top_bottom_spread_consistency: Double? = null,
    val delta_score_vs_base: Double? = null,
    val delta_ic_vs_base: Double? = null,
    val beta3_stability: Double? = null,
    val p_value: Double? = null,
    val q_value: Double? = null,
    val sample_count: Int? = null,
    val filtfilt_lfilter_consistent: Boolean? = null,
    val regime: String? = null,
    // ---- ②裁判字段 ----
    val conclusion_level: String,     // A / B / C / Reject
    val qualified: Boolean,           // 是否进 A 类，机械计数依据（§12.4）
    val interpretation: String? = null,
    val failure_states: String? = null,
) {
    /** 落盘文件名（不含目录）：`{factor}__{Y}__h{horizon}__{band}__{state_id}.json`。 */
    fun fileName(): String = "${factor_name}__${target_y}__h${horizon}__${band}__${state_id}.json"

    companion object {
        /**
         * 由①的未裁判度量 [metric] + ②的裁判结论组装一张最终卡片。
         *
         * 这是②[org.shiroumi.strategy.research.pipeline.ResearchEvaluation] 唯一应走的组装入口：
         * 度量字段原样从 [metric] 摊平，裁判字段由②传入，保证"研究数据"与"裁判结论"清晰可溯。
         */
        fun from(
            metric: ResonanceMetric,
            conclusionLevel: String,
            qualified: Boolean,
            interpretation: String? = null,
            failureStates: String? = null,
        ): ResonanceCard {
            val id = metric.identity
            return ResonanceCard(
                factor_name = id.factor_name,
                factor_type = id.factor_type,
                factor_i = id.factor_i,
                factor_j = id.factor_j,
                target_y = id.target_y,
                horizon = id.horizon,
                band = id.band,
                stft_window = metric.stft_window,
                norm_version = metric.norm_version,
                state_window = metric.state_window,
                state_id = id.state_id,
                rolling_corr_mean = metric.rolling_corr_mean,
                rolling_corr_stability = metric.rolling_corr_stability,
                mean_coherence = metric.mean_coherence,
                max_coherence = metric.max_coherence,
                coherence_coverage = metric.coherence_coverage,
                phase_std = metric.phase_std,
                lead_days_lag = metric.lead_days_lag,
                lead_days_phase = metric.lead_days_phase,
                lead_relation_stable = metric.lead_relation_stable,
                beta = metric.beta,
                oos_ic = metric.oos_ic,
                rank_ic_oos = metric.rank_ic_oos,
                hit_rate = metric.hit_rate,
                baseline = metric.baseline,
                top_bottom_spread = metric.top_bottom_spread,
                top_bottom_spread_consistency = metric.top_bottom_spread_consistency,
                delta_score_vs_base = metric.delta_score_vs_base,
                delta_ic_vs_base = metric.delta_ic_vs_base,
                beta3_stability = metric.beta3_stability,
                p_value = metric.p_value,
                q_value = metric.q_value,
                sample_count = metric.sample_count,
                filtfilt_lfilter_consistent = metric.filtfilt_lfilter_consistent,
                regime = metric.regime,
                conclusion_level = conclusionLevel,
                qualified = qualified,
                interpretation = interpretation,
                failure_states = failureStates,
            )
        }
    }
}

package org.shiroumi.strategy.research.topic.factor.eval

import org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric

/**
 * 共振卡片的**独立客观裁判器** —— research 第二部分（对结果的评估）的核心。
 *
 * 它**独立于 agent 的研究迭代代码**：只读①产出的 [ResonanceMetric]（规范化产物），按执行手册
 * §12.4 的 12 项硬条件**逐项重新复核**，给出客观的 [CardEvaluation]（qualified + 分档 + 逐项明细）。
 *
 * 为什么独立：agent 的目标只有研究 goal 本身，不能既当运动员又当裁判。把评估抽离成固定脚本，
 * 才能给 agent 提供客观的好坏信号反馈下一轮，并杜绝"改裁判口径来抬高 Metric"的放水路径。
 * 裁判用的阈值集中在 [EvaluationThresholds]，agent 不得改。
 *
 * 复核策略：**零信任①**——不采信任何①可能自填的结论，全部用 [ResonanceMetric] 的原始度量字段重算。
 * 缺字段一律判该项不通过（fail-closed），并在 detail 写明，绝不"缺字段就放行"。
 */
object ResonanceEvaluator {

    /** 对单张卡片的度量做独立复核，返回逐项结果 + 分档。 */
    fun evaluate(metric: ResonanceMetric): CardEvaluation {
        val pair = EvaluationThresholds.isPair(metric.identity.factor_type)
        val gates = buildList {
            add(gateMeanCoherence(metric))
            add(gateCoherenceCoverage(metric))
            add(gatePhaseStd(metric))
            add(gateLeadRelationStable(metric))
            add(gateLeadDaysInRange(metric))
            add(gateOosIc(metric))
            add(gateHitRate(metric))
            add(gateTopBottomConsistency(metric))
            add(gateQValue(metric))
            add(gateSampleCount(metric, pair))
            add(gateFiltfiltLfilter(metric))
            if (pair) addAll(gatesPairExtra(metric))
        }
        val failed = gates.filterNot { it.passed }.map { it.gate }
        val qualified = failed.isEmpty()
        val level = classify(metric, qualified)
        return CardEvaluation(
            identityFile = fileNameOf(metric),
            gates = gates,
            qualified = qualified,
            conclusionLevel = level,
            failedGates = failed,
        )
    }

    // ---- 分档（手册 §6.2/§6.4/§6.5 降档规则）----

    private fun classify(m: ResonanceMetric, qualified: Boolean): String {
        // Reject 硬否决：看到了未来 / 领先关系滞后 —— 无预测意义，最严重，优先于一切
        if (m.filtfilt_lfilter_consistent == false) return "Reject" // §6.4：filtfilt 显著但 lfilter 消失
        m.lead_days_lag?.let { if (it < -0.5) return "Reject" }      // §6.5：滞后
        if (qualified) return "A"
        // 同步（无领先但有解释性）→ C 类
        m.lead_days_lag?.let { if (it in -0.5..0.5) return "C" }
        // 其余"接近但有瑕疵"（如 lead_relation_stable=false、个别度量不达标）→ B 类
        return "B"
    }

    // ---- 逐项硬条件（全部 fail-closed：缺字段判不通过）----

    private fun gateMeanCoherence(m: ResonanceMetric) = numericGate(
        1, "mean_coherence>${EvaluationThresholds.MEAN_COHERENCE_MIN}", m.mean_coherence,
    ) { it > EvaluationThresholds.MEAN_COHERENCE_MIN }

    private fun gateCoherenceCoverage(m: ResonanceMetric) = numericGate(
        2, "coherence_coverage>${EvaluationThresholds.COHERENCE_COVERAGE_MIN}", m.coherence_coverage,
    ) { it > EvaluationThresholds.COHERENCE_COVERAGE_MIN }

    private fun gatePhaseStd(m: ResonanceMetric) = numericGate(
        3, "phase_std<π/4", m.phase_std,
    ) { it < EvaluationThresholds.PHASE_STD_MAX }

    private fun gateLeadRelationStable(m: ResonanceMetric): GateResult {
        val v = m.lead_relation_stable
        return GateResult(4, "lead_relation_stable=true", v == true,
            if (v == null) "缺 lead_relation_stable" else "lead_relation_stable=$v")
    }

    private fun gateLeadDaysInRange(m: ResonanceMetric): GateResult {
        val lead = m.lead_days_lag
            ?: return GateResult(5, "lead_days_lag∈horizon区间", false, "缺 lead_days_lag")
        val range = EvaluationThresholds.leadDaysRange(m.identity.horizon)
        val ok = lead in range
        return GateResult(5, "lead_days_lag∈$range", ok, "lead_days_lag=$lead, 区间=$range")
    }

    private fun gateOosIc(m: ResonanceMetric) = numericGate(
        6, "oos_ic>${EvaluationThresholds.OOS_IC_MIN}", m.oos_ic,
    ) { it > EvaluationThresholds.OOS_IC_MIN }

    private fun gateHitRate(m: ResonanceMetric): GateResult {
        val hr = m.hit_rate
        val base = m.baseline
        if (hr == null || base == null) {
            return GateResult(7, "hit_rate>baseline+${EvaluationThresholds.HIT_RATE_LIFT}", false,
                "缺 ${if (hr == null) "hit_rate " else ""}${if (base == null) "baseline" else ""}".trim())
        }
        val ok = hr > base + EvaluationThresholds.HIT_RATE_LIFT
        return GateResult(7, "hit_rate>baseline+${EvaluationThresholds.HIT_RATE_LIFT}", ok,
            "hit_rate=$hr, baseline=$base, 阈值=${base + EvaluationThresholds.HIT_RATE_LIFT}")
    }

    private fun gateTopBottomConsistency(m: ResonanceMetric): GateResult {
        // 「稳定为正」= 滚动同号占比 > 70% 且方向为正
        val cons = m.top_bottom_spread_consistency
        val spread = m.top_bottom_spread
        if (cons == null || spread == null) {
            return GateResult(8, "top_bottom_spread稳定为正(同号>70%)", false,
                "缺 ${if (cons == null) "top_bottom_spread_consistency " else ""}${if (spread == null) "top_bottom_spread" else ""}".trim())
        }
        val ok = cons > EvaluationThresholds.TOP_BOTTOM_CONSISTENCY_MIN && spread > 0
        return GateResult(8, "top_bottom_spread稳定为正(同号>70%)", ok,
            "consistency=$cons(阈>0.70), spread=$spread(需>0)")
    }

    private fun gateQValue(m: ResonanceMetric) = numericGate(
        9, "q_value<${EvaluationThresholds.Q_VALUE_MAX}", m.q_value,
    ) { it < EvaluationThresholds.Q_VALUE_MAX }

    private fun gateSampleCount(m: ResonanceMetric, pair: Boolean): GateResult {
        val min = if (pair) EvaluationThresholds.SAMPLE_COUNT_MIN_PAIR else EvaluationThresholds.SAMPLE_COUNT_MIN_SINGLE
        val n = m.sample_count
            ?: return GateResult(10, "sample_count>=$min", false, "缺 sample_count")
        return GateResult(10, "sample_count>=$min", n >= min, "sample_count=$n, 门槛=$min(${if (pair) "因子对" else "单因子"})")
    }

    private fun gateFiltfiltLfilter(m: ResonanceMetric): GateResult {
        val v = m.filtfilt_lfilter_consistent
        return GateResult(11, "filtfilt_lfilter_consistent=true", v == true,
            if (v == null) "缺 filtfilt_lfilter_consistent" else "filtfilt_lfilter_consistent=$v")
    }

    private fun gatesPairExtra(m: ResonanceMetric): List<GateResult> {
        val deltaIc = numericGate(
            12, "[pair]delta_ic_vs_base>${EvaluationThresholds.DELTA_IC_VS_BASE_MIN}", m.delta_ic_vs_base,
        ) { it > EvaluationThresholds.DELTA_IC_VS_BASE_MIN }
        val beta3 = numericGate(
            12, "[pair]beta3_stability>${EvaluationThresholds.BETA3_STABILITY_MIN}", m.beta3_stability,
        ) { it > EvaluationThresholds.BETA3_STABILITY_MIN }
        return listOf(deltaIc, beta3)
    }

    // ---- 工具 ----

    private inline fun numericGate(
        gate: Int, name: String, value: Double?, predicate: (Double) -> Boolean,
    ): GateResult =
        if (value == null) GateResult(gate, name, false, "缺字段")
        else GateResult(gate, name, predicate(value), "实际值=$value")

    private fun fileNameOf(m: ResonanceMetric): String = m.identity.fileName()
}

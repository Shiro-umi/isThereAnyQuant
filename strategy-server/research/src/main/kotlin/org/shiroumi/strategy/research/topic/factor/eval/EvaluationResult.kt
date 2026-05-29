package org.shiroumi.strategy.research.topic.factor.eval

import kotlinx.serialization.Serializable

/** 单条硬条件的复核结果。 */
@Serializable
data class GateResult(
    /** 条件编号，对齐手册 §12.4 的 1..12。 */
    val gate: Int,
    /** 条件可读名（如 "mean_coherence>0.5"）。 */
    val name: String,
    /** 是否通过。 */
    val passed: Boolean,
    /** 复核说明：实际值 vs 阈值，或缺字段原因。失败时给出可读理由，便于反馈给下一轮迭代。 */
    val detail: String,
)

/**
 * 一张卡片的独立评估结论。
 *
 * 这是 eval（research 第二部分）对**单个** [org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric]
 * 的客观裁判产物：逐项 §12.4 复核 + 最终分档。它独立于①的迭代代码，agent 碰不到判定逻辑。
 *
 * @property gates       12 项（单因子）或含因子对额外项的逐项复核明细
 * @property qualified   全部硬条件通过 → true（= A 类）
 * @property conclusionLevel  A / B / C / Reject（见 [ResonanceEvaluator] 的降档规则）
 * @property failedGates 未通过的条件编号，供快速定位"差在哪"
 */
@Serializable
data class CardEvaluation(
    val identityFile: String,        // ResonanceIdentity.fileName()，定位是哪张卡片
    val gates: List<GateResult>,
    val qualified: Boolean,
    val conclusionLevel: String,
    val failedGates: List<Int>,
)

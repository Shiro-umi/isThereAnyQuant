package org.shiroumi.strategy.research.tuner.core

import kotlinx.serialization.Serializable

/**
 * 一次试探的观测结果。
 *
 * 不同优化器（黑盒/可微）共享同一份"观测语义"：吃一组参数，得到一个标量度量 + 是否合格 + 诊断信息。
 *
 * @property score    待**最大化**的标量度量（如 mean_coherence、oos_ic）。
 *                    内部最小化算法会在适配器层取负号。
 * @property qualified 是否通过该研究 topic 的硬条件（来自 [ResearchEvaluation] 等评估器）。
 *                    qualified 不直接驱动优化，仅作为日志/早停的辅助信号。
 * @property detail   失败原因或诊断说明，给 agent 阅读用，不参与优化决策。
 */
@Serializable
data class Observation(
    val score: Double,
    val qualified: Boolean = false,
    val detail: String = "",
)

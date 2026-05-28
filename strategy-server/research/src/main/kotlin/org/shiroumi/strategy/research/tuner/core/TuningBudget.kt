package org.shiroumi.strategy.research.tuner.core

import kotlinx.serialization.Serializable

/**
 * 一次调优的预算与早停策略。
 *
 * 任何优化器（黑盒/可微）共享同一份预算语义：最大迭代次数 + 早停耐心 + 可选的目标阈值。
 *
 * @property maxIter   最大迭代次数。对黑盒优化器 = 试探次数；对可微优化器 = 梯度步数。
 * @property patience  连续 [patience] 轮没有改善则提前终止；0 表示禁用早停。
 * @property minDelta  改善阈值；新得分必须比历史最好得分高出 [minDelta] 才算"有改善"。
 * @property targetScore 达到该得分立即收敛终止；null 表示禁用此条件。
 */
@Serializable
data class TuningBudget(
    val maxIter: Int = 50,
    val patience: Int = 10,
    val minDelta: Double = 1e-6,
    val targetScore: Double? = null,
) {
    init {
        require(maxIter > 0) { "maxIter 必须 > 0：$maxIter" }
        require(patience >= 0) { "patience 必须 >= 0：$patience" }
        require(minDelta >= 0.0) { "minDelta 必须 >= 0：$minDelta" }
    }
}

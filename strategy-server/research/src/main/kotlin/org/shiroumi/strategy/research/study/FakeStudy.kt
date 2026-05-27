package org.shiroumi.strategy.research.study

import org.shiroumi.strategy.research.output.ResonanceIdentity
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStudy

/**
 * 占位研究内容（子模块 ①），仅用于**跑通管线骨架的端到端闭环**。
 *
 * 严格边界：这不是真实研究，也**不做裁判**。它恒定产出一条**未裁判的原始度量**
 * [ResonanceMetric]，证明 Source → … → Study → Evaluation → Output 链路能闭合。
 *
 * 真实的 [ResearchStudy] 实现（因子计算、Y 标签、状态划分、STFT 分频、相干性/领先、permutation、
 * OOS 验证，诚实算出各项度量）由 **autoresearch** 在此插槽内填充。是否合格交给
 * [org.shiroumi.strategy.research.study.FakeEvaluation] 那一层（②）裁判。
 */
class FakeStudy : ResearchStudy<Unit, List<ResonanceMetric>> {

    override val name: String = "fake-study-skeleton"

    override fun run(ctx: ResearchContext, input: Unit): List<ResonanceMetric> = listOf(
        ResonanceMetric(
            identity = ResonanceIdentity(
                factor_name = "FAKE",
                factor_type = "single",
                factor_i = "FAKE",
                target_y = "Y2",
                horizon = 3,
                band = "F2a",
                state_id = "trend=mid,disp=mid,vol=mid",
            ),
            stft_window = 40,
            norm_version = "skeleton",
            state_window = 60,
            // 骨架阶段不产出任何真实度量，全部留空
        ),
    )
}

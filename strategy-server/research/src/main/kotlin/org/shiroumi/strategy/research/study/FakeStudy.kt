package org.shiroumi.strategy.research.study

import org.shiroumi.strategy.research.output.ResonanceCard
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStudy

/**
 * 占位研究内容，仅用于**跑通管线骨架的端到端闭环**（T7）。
 *
 * 严格边界：这不是真实研究。它恒定产出一张 `qualified=false` 的样例卡片，
 * 证明 Source → … → Study → Output → 计数 的链路能闭合，且 Metric 脚本对未合格卡片输出 0。
 *
 * 真实的 [ResearchStudy] 实现（因子计算、Y 标签、状态划分、STFT 分频、相干性/领先、permutation、
 * OOS 验证、按 §12.4 判定 qualified）由 **autoresearch** 在此插槽内填充，不属于本基建的职责。
 */
class FakeStudy : ResearchStudy<Unit, List<ResonanceCard>> {

    override val name: String = "fake-study-skeleton"

    override fun run(ctx: ResearchContext, input: Unit): List<ResonanceCard> = listOf(
        ResonanceCard(
            factor_name = "FAKE",
            factor_type = "single",
            factor_i = "FAKE",
            target_y = "Y2",
            horizon = 3,
            band = "F2a",
            stft_window = 40,
            norm_version = "skeleton",
            state_window = 60,
            state_id = "trend=mid,disp=mid,vol=mid",
            conclusion_level = "Reject",
            qualified = false, // 骨架阶段不产出任何真实结论
            interpretation = "管线骨架自检占位卡片，非研究结论。",
        ),
    )
}

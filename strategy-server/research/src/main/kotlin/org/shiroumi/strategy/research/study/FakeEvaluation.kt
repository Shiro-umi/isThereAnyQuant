package org.shiroumi.strategy.research.study

import org.shiroumi.strategy.research.output.ResonanceCard
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchEvaluation

/**
 * 占位评估器（子模块 ②），仅用于跑通骨架。
 *
 * 吃 [FakeStudy] 产出的未裁判度量，恒定裁成 `qualified=false` 的样例卡片，
 * 证明 ①Study → ②Evaluation → Output 的裁判链路闭合，且 Metric 脚本对未合格卡片输出 0。
 *
 * 真实评估器（按执行手册 §12.4 的 12 项硬条件对照①的度量裁出 qualified、分档）由 **autoresearch**
 * 在此插槽内实现。它只读度量、对照阈值，**不得回头改研究逻辑或放宽硬条件骗 Metric**。
 */
class FakeEvaluation : ResearchEvaluation<List<ResonanceMetric>, List<ResonanceCard>> {

    override val name: String = "fake-evaluation-skeleton"

    override fun run(ctx: ResearchContext, input: List<ResonanceMetric>): List<ResonanceCard> =
        input.map { metric ->
            ResonanceCard.from(
                metric = metric,
                conclusionLevel = "Reject",
                qualified = false, // 骨架阶段不裁出任何真实合格结论
                interpretation = "管线骨架自检占位卡片，非研究结论。",
            )
        }
}

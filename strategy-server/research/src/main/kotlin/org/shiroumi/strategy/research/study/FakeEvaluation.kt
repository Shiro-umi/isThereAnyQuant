package org.shiroumi.strategy.research.study

import org.shiroumi.strategy.research.eval.ResonanceEvaluator
import org.shiroumi.strategy.research.output.ResonanceCard
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchEvaluation

/**
 * 评估段的骨架接线（②插槽）：把①的度量交给**独立裁判器** [ResonanceEvaluator] 客观复核，
 * 再按裁判结论组装落盘卡片。
 *
 * 注意定位变化：评估逻辑本身不在这里，而在固定的 [ResonanceEvaluator]（research 第二部分，基建侧）。
 * 这一层只做"接线"——调裁判器、把 [org.shiroumi.strategy.research.eval.CardEvaluation] 映射成
 * [ResonanceCard]。agent 实现真实研究时，①Study 算度量，本段不变，裁判仍由独立 evaluator 负责，
 * 杜绝 agent 自我裁判放水。
 *
 * 骨架阶段：[FakeStudy] 给的是空度量，evaluator 会如实裁成 Reject（qualified=false），
 * 故落盘后 `count-resonance-cards.sh` 仍输出 0。
 */
class FakeEvaluation : ResearchEvaluation<List<ResonanceMetric>, List<ResonanceCard>> {

    override val name: String = "evaluation:resonance-evaluator"

    override fun run(ctx: ResearchContext, input: List<ResonanceMetric>): List<ResonanceCard> =
        input.map { metric ->
            val verdict = ResonanceEvaluator.evaluate(metric)
            ResonanceCard.from(
                metric = metric,
                conclusionLevel = verdict.conclusionLevel,
                qualified = verdict.qualified,
                interpretation = if (verdict.qualified) null
                else "未通过硬条件：${verdict.failedGates.joinToString(",")}（详见 eval 明细）",
            )
        }
}

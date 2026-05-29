package org.shiroumi.strategy.research.topic.factor.study

import org.shiroumi.strategy.research.topic.factor.eval.ResonanceEvaluator
import org.shiroumi.strategy.research.topic.factor.output.ResonanceCard
import org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchEvaluation

/**
 * 因子挖掘 topic 的裁判器（②）—— 包装 factor 层通用 [ResonanceEvaluator]（12 项硬条件）。
 *
 * 与 trend 的 SentimentEvaluation 同构：吃①Study 的未裁判 [ResonanceMetric]，
 * 按同一套通用因子裁判口径裁出 [ResonanceCard]。量价因子与情绪因子共用裁判，结论横向可比。
 */
class FactorEvaluation : ResearchEvaluation<List<ResonanceMetric>, List<ResonanceCard>> {
    override val name: String = "evaluation:volume-price-factor-evaluator"

    override fun run(ctx: ResearchContext, input: List<ResonanceMetric>): List<ResonanceCard> =
        input.map { metric ->
            val verdict = ResonanceEvaluator.evaluate(metric)
            ResonanceCard.from(
                metric = metric,
                conclusionLevel = verdict.conclusionLevel,
                qualified = verdict.qualified,
                interpretation = if (verdict.qualified) null
                else "未通过硬条件：${verdict.failedGates.joinToString(",")}（详见 eval 明细）",
                failureStates = verdict.failedGates.joinToString(",").ifBlank { null },
            )
        }
}

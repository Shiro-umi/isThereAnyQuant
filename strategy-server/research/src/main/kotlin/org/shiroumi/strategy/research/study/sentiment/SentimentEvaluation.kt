package org.shiroumi.strategy.research.study.sentiment

import org.shiroumi.strategy.research.eval.ResonanceEvaluator
import org.shiroumi.strategy.research.output.ResonanceCard
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchEvaluation

class SentimentEvaluation : ResearchEvaluation<List<ResonanceMetric>, List<ResonanceCard>> {
    override val name: String = "evaluation:sentiment-resonance-evaluator"

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

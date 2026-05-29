package org.shiroumi.strategy.research.topic.factor.study

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import org.shiroumi.strategy.research.topic.factor.output.ResonanceCardWriter
import org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric
import java.nio.file.Path

/**
 * 因子挖掘 topic 的研究管线入口 —— 量能 / 量价关系因子。
 *
 * 与 trend 的 `SentimentResearchPipeline` 平级：
 * `[VolumePriceFactorStudy]（①算度量）→ [FactorEvaluation]（②按通用 ResonanceEvaluator 裁判）→ ResonanceCardWriter（落盘）`。
 *
 * 复用 factor 层通用裁判 [org.shiroumi.strategy.research.topic.factor.eval.ResonanceEvaluator]
 * 与通用产物契约 [org.shiroumi.strategy.research.topic.factor.output.ResonanceCard]。
 */
object FactorResearchPipeline {

    fun build(): ResearchPipeline<Unit, List<Path>> {
        val study = VolumePriceFactorStudy()
        val evaluation = FactorEvaluation()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceMetric>>(study.name, study)
            .andThen(evaluation.name, evaluation)
            .andThen("output:resonance-card-writer", writer)
    }

    fun run(ctx: ResearchContext): List<Path> = build().run(ctx, Unit)
}

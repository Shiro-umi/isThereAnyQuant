package org.shiroumi.strategy.research.study.sentiment

import org.shiroumi.quant_kmp.strategy.daily.SentimentFactorApiLayer
import org.shiroumi.strategy.research.api.DbSentimentFactorApiLayer
import org.shiroumi.strategy.research.output.ResonanceCardWriter
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import java.nio.file.Path

object SentimentResearchPipeline {
    fun build(apiLayer: SentimentFactorApiLayer = DbSentimentFactorApiLayer()): ResearchPipeline<Unit, List<Path>> {
        val study = SentimentResonanceStudy(apiLayer)
        val evaluation = SentimentEvaluation()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceMetric>>(study.name, study)
            .andThen(evaluation.name, evaluation)
            .andThen("output:resonance-card-writer", writer)
    }

    fun run(ctx: ResearchContext, apiLayer: SentimentFactorApiLayer = DbSentimentFactorApiLayer()): List<Path> =
        build(apiLayer).run(ctx, Unit)
}

package org.shiroumi.strategy.research.study.sentiment

import org.shiroumi.strategy.research.output.ResonanceCardWriter
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import java.nio.file.Path

object SentimentResearchPipeline {
    fun build(): ResearchPipeline<Unit, List<Path>> {
        val study = SentimentResonanceStudy()
        val evaluation = SentimentEvaluation()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceMetric>>(study.name, study)
            .andThen(evaluation.name, evaluation)
            .andThen("output:resonance-card-writer", writer)
    }

    fun run(ctx: ResearchContext): List<Path> = build().run(ctx, Unit)
}

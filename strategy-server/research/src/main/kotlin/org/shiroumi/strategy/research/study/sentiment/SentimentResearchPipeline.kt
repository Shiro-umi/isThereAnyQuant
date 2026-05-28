package org.shiroumi.strategy.research.study.sentiment

import org.shiroumi.quant_kmp.strategy.daily.FactorDataSource
import org.shiroumi.strategy.research.output.ResonanceCardWriter
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import org.shiroumi.strategy.research.source.DbFactorDataSource
import java.nio.file.Path

object SentimentResearchPipeline {
    fun build(dataSource: FactorDataSource = DbFactorDataSource()): ResearchPipeline<Unit, List<Path>> {
        val study = SentimentResonanceStudy(dataSource)
        val evaluation = SentimentEvaluation()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceMetric>>(study.name, study)
            .andThen(evaluation.name, evaluation)
            .andThen("output:resonance-card-writer", writer)
    }

    fun run(ctx: ResearchContext, dataSource: FactorDataSource = DbFactorDataSource()): List<Path> =
        build(dataSource).run(ctx, Unit)
}

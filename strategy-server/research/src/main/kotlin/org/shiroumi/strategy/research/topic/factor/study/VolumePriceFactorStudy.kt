package org.shiroumi.strategy.research.topic.factor.study

import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.database.sentiment.SentimentTargetLabelCalculator
import org.shiroumi.database.sentiment.SentimentTargetLabels
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStudy
import org.shiroumi.strategy.research.topic.factor.output.ResonanceCore
import org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric

/**
 * 因子挖掘 topic 的研究迭代插槽（①Study）—— **量能 / 量价关系因子**。
 *
 * 设计依据：`private/research-docs/volume-price-factor-formula.html`。
 * 架构（先聚合后推导）：
 * - 数据预备由 [org.shiroumi.database.sentiment.VolumePriceMarketCalculator] 完成，把全市场**等权**
 *   聚合成两条市场级基础序列 `vpmRet`（对数收益）/ `vpmTurn`（换手率），落 `sentiment_factor_daily`。
 * - 本 Study 从这两条序列用 [VolumePriceFactors] 推导出 13 条因子序列（VV/VP），
 *   再对每个因子 × 目标Y × horizon × 频带调 [ResonanceCore.buildMetric] 算共振度量。
 * - 目标 Y 沿用 sentiment 的 Y1/Y2/Y3（同表已落库），保证量价因子结论与情绪因子横向可比。
 *
 * 只算度量、不裁判（裁判由 [org.shiroumi.strategy.research.topic.factor.eval.ResonanceEvaluator] 经
 * FactorEvaluation 完成）。产物沿用 factor 层通用契约 [ResonanceMetric]。
 */
class VolumePriceFactorStudy : ResearchStudy<Unit, List<ResonanceMetric>> {

    override val name: String = "study:volume-price-factor"

    override fun run(ctx: ResearchContext, input: Unit): List<ResonanceMetric> {
        val records = SentimentFactorDailyRepository.findBetween(ctx.startDate, ctx.endDate)
            .sortedBy { it.tradeDate }
        return runRecords(ctx, records)
    }

    internal fun runRecords(ctx: ResearchContext, records: List<SentimentFactorDailyRecord>): List<ResonanceMetric> {
        if (records.isEmpty()) return emptyList()

        val ret = records.map { it.vpmRet }
        val turn = records.map { it.vpmTurn }
        val factorSeries = VolumePriceFactors.buildAll(ret, turn)

        val labels = SentimentTargetLabelCalculator.build(records)
        val targets = ctx.param("targets", "Y1,Y2,Y3").split(',').map { it.trim() }.filter { it in TARGETS }
        val horizons = ctx.param("horizons", "1,3,5").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in setOf(1, 3, 5) }
        val bands = ctx.param("bands", "F1b,F2a").split(',').map { it.trim() }.filter { it in ResonanceCore.BANDS.keys }
        val factorNames = ctx.param("vp-factors", factorSeries.keys.joinToString(","))
            .split(',').map { it.trim() }.filter { it in factorSeries.keys }

        val metrics = ArrayList<ResonanceMetric>()
        for (factor in factorNames) {
            val rawFactor = factorSeries.getValue(factor)
            for (target in targets) {
                val rawTarget = records.map { targetRaw(it, target) }
                for (horizon in horizons) {
                    val futureTarget = labels.map { targetNext(it, target, horizon) }
                    for (band in bands) {
                        ResonanceCore.buildMetric(
                            ctx = ctx,
                            factor = factor,
                            target = target,
                            horizon = horizon,
                            band = band,
                            rawFactor = rawFactor,
                            rawTarget = rawTarget,
                            futureTarget = futureTarget,
                            stateId = "trend=all,disp=all,vol=all",
                            stateIndexes = null,
                        )?.let { metrics += it.metric }
                    }
                }
            }
        }
        return metrics
    }

    private fun targetRaw(record: SentimentFactorDailyRecord, target: String): Double? =
        when (target) {
            "Y1" -> record.y1Raw
            "Y2" -> record.y2Raw
            "Y3" -> record.y3Raw
            else -> null
        }

    private fun targetNext(label: SentimentTargetLabels, target: String, horizon: Int): Double? =
        when (target to horizon) {
            "Y1" to 1 -> label.y1Next1
            "Y1" to 3 -> label.y1Next3
            "Y1" to 5 -> label.y1Next5
            "Y2" to 1 -> label.y2Next1
            "Y2" to 3 -> label.y2Next3
            "Y2" to 5 -> label.y2Next5
            "Y3" to 1 -> label.y3Next1
            "Y3" to 3 -> label.y3Next3
            "Y3" to 5 -> label.y3Next5
            else -> null
        }

    companion object {
        private val TARGETS = setOf("Y1", "Y2", "Y3")
    }
}

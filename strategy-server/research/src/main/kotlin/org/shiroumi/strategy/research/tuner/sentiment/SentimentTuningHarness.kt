package org.shiroumi.strategy.research.tuner.sentiment

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousDim
import org.shiroumi.strategy.research.tuner.blackbox.ContinuousSpace
import org.shiroumi.strategy.research.tuner.blackbox.NelderMeadOptimizer
import org.shiroumi.strategy.research.tuner.core.ObjectiveFunction
import org.shiroumi.strategy.research.tuner.core.Observation
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import org.shiroumi.strategy.research.tuner.core.TuningResult
import kotlin.math.min

/**
 * 次日情绪公式调优的完整流水线。
 *
 * 消费 sentiment_factor_daily 表数据，用 NelderMead 黑盒优化器搜索 11 个 θ。
 * 训练集（2020–2023）上 walk-forward 平均 hit rate 为优化目标；
 * 验证集（2024）作为过拟合哨兵；测试集（2025+）封箱评估。
 */
class SentimentTuningHarness(
    private val trainStart: LocalDate = LocalDate(2020, 1, 2),
    private val trainEnd: LocalDate = LocalDate(2023, 12, 29),
    private val valStart: LocalDate = LocalDate(2024, 1, 2),
    private val valEnd: LocalDate = LocalDate(2024, 12, 31),
    private val testStart: LocalDate = LocalDate(2025, 1, 2),
) {
    private val allRecords: List<SentimentFactorDailyRecord> by lazy {
        SentimentFactorDailyRepository.findBetween(trainStart, LocalDate(2026, 12, 31))
    }

    /** 训练集上算出的固定窗口 W，val/test 共用以避免前视 */
    private val trainedWindow: Int by lazy {
        val y = trainRecords.map { it.yComposite ?: 0.0 }.toDoubleArray()
        val scorer = NextDaySentimentScorer(ThetaConfig.IDENTITY)
        // Use adaptive window on training data only
        scorer.adaptiveWindowFor(y)
    }

    private val trainRecords: List<SentimentFactorRecord> by lazy {
        toSentimentFactorRecords(allRecords.filter { it.tradeDate in trainStart..trainEnd })
    }

    private val valRecords: List<SentimentFactorRecord> by lazy {
        toSentimentFactorRecords(allRecords.filter { it.tradeDate in valStart..valEnd })
    }

    private val testRecords: List<SentimentFactorRecord> by lazy {
        toSentimentFactorRecords(allRecords.filter { it.tradeDate >= testStart })
    }

    /** 构建搜索空间（11 维连续参数） */
    fun buildSearchSpace(): ContinuousSpace = ContinuousSpace(
        ThetaConfig.SEARCH_BOUNDS.map { (key, bounds) ->
            val init = ThetaConfig.IDENTITY.let { t ->
                when (key) {
                    "thetaAlpha" -> t.thetaAlpha
                    "thetaLambda" -> t.thetaLambda
                    "thetaXi" -> t.thetaXi
                    "thetaGammaA" -> t.thetaGammaA
                    "thetaGammaB" -> t.thetaGammaB
                    "thetaGammaC" -> t.thetaGammaC
                    "thetaBeta" -> t.thetaBeta
                    "thetaWA" -> t.thetaWA
                    "thetaWB" -> t.thetaWB
                    "thetaWC" -> t.thetaWC
                    "thetaTau" -> t.thetaTau
                    else -> 0.0
                }
            }
            ContinuousDim(key = key, lower = bounds.first, upper = bounds.second, initial = init)
        }
    )

    /** 返回在当前训练数据上闭合的 ObjectiveFunction */
    fun objective(): ObjectiveFunction {
        val train = trainRecords // capture once
        val w = trainedWindow       // capture W from training
        return ObjectiveFunction { _, params ->
            val theta = ThetaConfig.fromParams(params)
            val scorer = NextDaySentimentScorer(theta, fixedWindow = w)
            val scores = scorer.scoreSeries(train)
            val (hitRate, _, coverage) = evaluate(scores, train)
            val detail = "hit=%.4f coverage=%d W=$w".format(hitRate, coverage)
            Observation(score = hitRate, qualified = coverage > 30, detail = detail)
        }
    }

    /** 在验证集上评估 */
    fun evaluateOnVal(theta: ThetaConfig): EvalResult =
        evaluateWithWindow(theta, valRecords)

    /** 在测试集上评估 */
    fun evaluateOnTest(theta: ThetaConfig): EvalResult =
        evaluateWithWindow(theta, testRecords)

    /** 在训练集上评估（初始基线） */
    fun evaluateOnTrain(theta: ThetaConfig): EvalResult =
        evaluateWithWindow(theta, trainRecords)

    private fun evaluateWithWindow(theta: ThetaConfig, records: List<SentimentFactorRecord>): EvalResult {
        val scorer = NextDaySentimentScorer(theta, fixedWindow = trainedWindow)
        val scores = scorer.scoreSeries(records)
        return evaluate(scores, records)
    }

    companion object {
        private fun toSentimentFactorRecords(records: List<SentimentFactorDailyRecord>): List<SentimentFactorRecord> =
            records.map { r ->
                SentimentFactorRecord(
                    tradeDate = r.tradeDate.toString(),
                    factors = r.factors,
                    y1Raw = r.y1Raw,
                    y2Raw = r.y2Raw,
                    y3Raw = r.y3Raw,
                    yComposite = r.yComposite,
                )
            }

        /**
         * 评估 score 序列。
         * 对每个有 score 的日子，取次日 ΔY 做方向判断。
         */
        fun evaluate(scores: DoubleArray, records: List<SentimentFactorRecord>): EvalResult {
            var correct = 0
            var total = 0
            var scoreSum = 0.0
            var scoreCount = 0
            for (t in 0 until min(scores.size, records.size - 1)) {
                val score = scores[t]
                if (!score.isFinite()) continue
                scoreSum += score
                scoreCount++
                val yToday = records[t].yComposite ?: continue
                val yNext = records[t + 1].yComposite ?: continue
                val deltaY = yNext - yToday
                if (deltaY == 0.0) continue // neutral, skip
                val actual = deltaY > 0.0
                val predicted = score > 0.50
                total++
                if (predicted == actual) correct++
            }
            val hitRate = if (total > 0) correct.toDouble() / total.toDouble() else 0.0
            val meanScore = if (scoreCount > 0) scoreSum / scoreCount else 0.0
            val coverage = total
            return EvalResult(hitRate = hitRate, meanScore = meanScore, coverage = coverage)
        }
    }
}

data class EvalResult(
    val hitRate: Double,
    val meanScore: Double,
    val coverage: Int,
)

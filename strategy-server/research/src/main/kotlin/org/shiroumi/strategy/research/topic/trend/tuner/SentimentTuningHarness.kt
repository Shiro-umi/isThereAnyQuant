package org.shiroumi.strategy.research.topic.trend.tuner

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

    /**
     * 双向目标函数：以「次日 a1 方向」为标的，最大化 **多头/空头两侧命中率的最小值**（max-min）。
     *
     * 这样优化器无法靠天然占多数的上行样本蹭分——必须同时把看涨日真涨率与看跌日真跌率拉高，
     * 才能抬升 min。轻度奖励两侧之和以打破 min 相等时的平台。
     * 任一侧样本不足（< 40 天）则判 unqualified，避免「全看涨/全看跌」的退化解。
     */
    fun objectiveVsMarket(): ObjectiveFunction {
        val train = trainRecords
        val w = trainedWindow
        return ObjectiveFunction { _, params ->
            val theta = ThetaConfig.fromParams(params)
            val scorer = NextDaySentimentScorer(theta, fixedWindow = w)
            val scores = scorer.scoreSeries(train)
            val r = evaluateMarket(scores, train)
            val long = if (r.longHit.isNaN()) 0.0 else r.longHit
            val short = if (r.shortHit.isNaN()) 0.0 else r.shortHit
            val balanced = r.longDays >= 40 && r.shortDays >= 40
            // max-min 主目标 + 1e-3·(两侧和) 平台破除
            val score = min(long, short) + 1e-3 * (long + short)
            val detail = "long=%.4f(%d) short=%.4f(%d) min=%.4f".format(long, r.longDays, short, r.shortDays, min(long, short))
            Observation(score = score, qualified = balanced, detail = detail)
        }
    }

    /**
     * 双向目标 m2：train + val 联合 max-min，抗过拟合。
     *
     * 对 train 与 val 分别算双向命中，取「四个数（train多/train空/val多/val空）的最小值」为主目标。
     * 强制优化器找到一组在两个时间段都双向稳健的 θ，压制 m1 中 test 空头侧的泛化回落。
     */
    fun objectiveVsMarketJoint(): ObjectiveFunction {
        val train = trainRecords
        val valRec = valRecords
        val w = trainedWindow
        return ObjectiveFunction { _, params ->
            val theta = ThetaConfig.fromParams(params)
            val scorer = NextDaySentimentScorer(theta, fixedWindow = w)
            val rt = evaluateMarket(scorer.scoreSeries(train), train)
            val rv = evaluateMarket(scorer.scoreSeries(valRec), valRec)
            fun safe(x: Double) = if (x.isNaN()) 0.0 else x
            val four = listOf(safe(rt.longHit), safe(rt.shortHit), safe(rv.longHit), safe(rv.shortHit))
            val balanced = rt.longDays >= 40 && rt.shortDays >= 40 && rv.longDays >= 20 && rv.shortDays >= 20
            val score = four.min() + 1e-3 * four.sum()
            val detail = "tL=%.3f tS=%.3f vL=%.3f vS=%.3f min=%.3f".format(
                safe(rt.longHit), safe(rt.shortHit), safe(rv.longHit), safe(rv.shortHit), four.min())
            Observation(score = score, qualified = balanced, detail = detail)
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

    /**
     * 以「次日市值加权涨跌幅 a1（=y1Raw）的双向方向」为标的评估预测力。
     *
     * 与 [evaluate] 不同：这里判定的是 **次日 a1 本身的涨跌符号**（次日市场是涨是跌），
     * 而非 yComposite 的变化方向。回答的问题是：
     * score 看涨时次日市场真涨吗？score 看跌时次日市场真跌吗？两侧必须各自跑赢天然基准。
     */
    fun evaluateVsMarket(theta: ThetaConfig, records: List<SentimentFactorRecord>): MarketEvalResult {
        val scorer = NextDaySentimentScorer(theta, fixedWindow = trainedWindow)
        val scores = scorer.scoreSeries(records)
        return evaluateMarket(scores, records)
    }

    fun valRecordsForMarket() = valRecords
    fun testRecordsForMarket() = testRecords
    fun trainRecordsForMarket() = trainRecords

    /** 全样本（2020+）记录，按日期升序——供跨集拐点分析使用 */
    fun allRecordsForMarket(): List<SentimentFactorRecord> =
        toSentimentFactorRecords(allRecords.filter { it.tradeDate >= trainStart })

    /** 用给定 θ 在整段 records 上算 score（固定窗口，无前视） */
    fun scoreSeriesOf(theta: ThetaConfig, records: List<SentimentFactorRecord>): DoubleArray =
        NextDaySentimentScorer(theta, fixedWindow = trainedWindow).scoreSeries(records)

    /**
     * 双阈值评估：score > [tauLong] 看涨、score < [tauShort] 看跌、中间弃权。
     * 用更严格的看跌门槛（tauShort < 0.5）换空头侧精度，代价是覆盖率下降。
     */
    fun evaluateVsMarketDual(
        theta: ThetaConfig,
        records: List<SentimentFactorRecord>,
        tauLong: Double,
        tauShort: Double,
    ): DualEvalResult {
        val scorer = NextDaySentimentScorer(theta, fixedWindow = trainedWindow)
        val scores = scorer.scoreSeries(records)
        var lHit = 0; var lTot = 0; var sHit = 0; var sTot = 0; var abstain = 0; var n = 0
        for (t in 0 until min(scores.size, records.size - 1)) {
            val s = scores[t]
            if (!s.isFinite()) continue
            val r = records[t + 1].y1Raw ?: continue
            if (r == 0.0) continue
            n++
            when {
                s > tauLong -> { lTot++; if (r > 0.0) lHit++ }
                s < tauShort -> { sTot++; if (r < 0.0) sHit++ }
                else -> abstain++
            }
        }
        return DualEvalResult(
            coverage = n, abstain = abstain,
            longDays = lTot, longHit = if (lTot > 0) lHit.toDouble() / lTot else Double.NaN,
            shortDays = sTot, shortHit = if (sTot > 0) sHit.toDouble() / sTot else Double.NaN,
            tauLong = tauLong, tauShort = tauShort,
        )
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

        /**
         * 以「次日 a1（市值加权涨跌幅）的涨跌符号」为标的的双向评估。
         *
         * 配对样本：对每个有限 score 的 t，取次日真实收益 r = records[t+1].y1Raw。
         * - 多头侧：score>0.5 的样本中，r>0 的比例（应跑赢天然上行率）
         * - 空头侧：score<0.5 的样本中，r<0 的比例（应跑赢天然下行率）
         * - IC：score 与 r 的 Pearson + Spearman
         * - 五档分层：按 score 排序均分 5 组，各组 r 均值（看是否单调）
         */
        fun evaluateMarket(scores: DoubleArray, records: List<SentimentFactorRecord>): MarketEvalResult {
            data class Pair2(val score: Double, val ret: Double)
            val pairs = ArrayList<Pair2>()
            for (t in 0 until min(scores.size, records.size - 1)) {
                val s = scores[t]
                if (!s.isFinite()) continue
                val r = records[t + 1].y1Raw ?: continue
                if (r == 0.0) continue
                pairs += Pair2(s, r)
            }
            val n = pairs.size
            if (n == 0) return MarketEvalResult.EMPTY

            // 天然基准：样本里次日上行 / 下行的占比
            val baseUp = pairs.count { it.ret > 0.0 }.toDouble() / n
            val baseDown = 1.0 - baseUp

            // 双向条件命中
            val longDays = pairs.filter { it.score > 0.50 }
            val shortDays = pairs.filter { it.score < 0.50 }
            val longHit = if (longDays.isNotEmpty()) longDays.count { it.ret > 0.0 }.toDouble() / longDays.size else Double.NaN
            val shortHit = if (shortDays.isNotEmpty()) shortDays.count { it.ret < 0.0 }.toDouble() / shortDays.size else Double.NaN

            // 二项检验（正态近似）：H0 = 命中率 == 基准；返回单尾 z 与 p
            fun binomP(hit: Double, k: Int, base: Double): Double {
                if (k == 0 || hit.isNaN()) return Double.NaN
                val mean = base
                val sd = kotlin.math.sqrt(base * (1 - base) / k)
                if (sd == 0.0) return Double.NaN
                val z = (hit - mean) / sd
                // 单尾上侧 p = 1 - Φ(z)，用 erf 近似
                return 0.5 * erfc(z / kotlin.math.sqrt(2.0))
            }
            val longP = binomP(longHit, longDays.size, baseUp)
            val shortP = binomP(shortHit, shortDays.size, baseDown)

            // IC
            val sArr = DoubleArray(n) { pairs[it].score }
            val rArr = DoubleArray(n) { pairs[it].ret }
            val pearson = pearson(sArr, rArr)
            val spearman = pearson(rank(sArr), rank(rArr))

            // 五档分层（按 score 升序均分）
            val sorted = pairs.sortedBy { it.score }
            val quintiles = DoubleArray(5)
            for (q in 0 until 5) {
                val from = q * n / 5
                val to = (q + 1) * n / 5
                val slice = sorted.subList(from, to)
                quintiles[q] = if (slice.isNotEmpty()) slice.sumOf { it.ret } / slice.size else Double.NaN
            }

            return MarketEvalResult(
                coverage = n,
                baseUp = baseUp, baseDown = baseDown,
                longDays = longDays.size, longHit = longHit, longP = longP,
                shortDays = shortDays.size, shortHit = shortHit, shortP = shortP,
                pearson = pearson, spearman = spearman,
                quintileReturns = quintiles,
            )
        }

        // ---- 统计工具 ----

        private fun pearson(x: DoubleArray, y: DoubleArray): Double {
            val n = x.size
            if (n < 2) return Double.NaN
            val mx = x.average(); val my = y.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in 0 until n) {
                val dx = x[i] - mx; val dy = y[i] - my
                sxy += dx * dy; sxx += dx * dx; syy += dy * dy
            }
            val denom = kotlin.math.sqrt(sxx * syy)
            return if (denom == 0.0) Double.NaN else sxy / denom
        }

        /** 秩（平均秩处理并列），用于 Spearman */
        private fun rank(v: DoubleArray): DoubleArray {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
                val avgRank = (i + j) / 2.0 + 1.0
                for (k in i..j) r[idx[k]] = avgRank
                i = j + 1
            }
            return r
        }

        /** 互补误差函数 erfc，Abramowitz-Stegun 7.1.26 近似 */
        private fun erfc(x: Double): Double {
            val z = kotlin.math.abs(x)
            val t = 1.0 / (1.0 + 0.5 * z)
            val ans = t * kotlin.math.exp(
                -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 +
                    t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 +
                    t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 +
                    t * 0.17087277))))))))
            )
            return if (x >= 0.0) ans else 2.0 - ans
        }
    }
}

data class EvalResult(
    val hitRate: Double,
    val meanScore: Double,
    val coverage: Int,
)

/**
 * 「次日 a1 双向方向」评估结果。主轴：longHit vs baseUp、shortHit vs baseDown 各自的净增益与显著性。
 */
data class MarketEvalResult(
    val coverage: Int,
    val baseUp: Double,
    val baseDown: Double,
    val longDays: Int,
    val longHit: Double,
    val longP: Double,
    val shortDays: Int,
    val shortHit: Double,
    val shortP: Double,
    val pearson: Double,
    val spearman: Double,
    val quintileReturns: DoubleArray,
) {
    companion object {
        val EMPTY = MarketEvalResult(0, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, DoubleArray(5) { Double.NaN })
    }
}

/** 双阈值（看涨/看跌/弃权）评估结果。 */
data class DualEvalResult(
    val coverage: Int,
    val abstain: Int,
    val longDays: Int,
    val longHit: Double,
    val shortDays: Int,
    val shortHit: Double,
    val tauLong: Double,
    val tauShort: Double,
)

package org.shiroumi.strategy.research.tuner.sentiment

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.strategy.research.tuner.blackbox.NelderMeadOptimizer
import org.shiroumi.strategy.research.tuner.core.TuningBudget
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NextDaySentimentScorerTest {

    /** 基线验证：用 θ=IDENTITY 在训练集上跑一次看 hit rate 是否 > 随机（50%） */
    @Test
    fun `baseline hit rate above random on training set`() {
        val harness = SentimentTuningHarness()
        val result = harness.evaluateOnTrain(ThetaConfig.IDENTITY)
        println("BASELINE train: hitRate=%.4f meanScore=%.4f coverage=%d".format(
            result.hitRate, result.meanScore, result.coverage))
        assertTrue(result.coverage > 0, "训练集至少应有 coverage")
        // 基线 hit rate 应 > 50%（随机）——否则数据或公式有根本性问题
        assertTrue(result.hitRate > 0.48, "基线 hit rate 应高于随机，got ${result.hitRate}")
    }

    /** 在验证集和测试集上各跑一次基线 */
    @Test
    fun `baseline hit rate on val and test sets`() {
        val harness = SentimentTuningHarness()
        val valResult = harness.evaluateOnVal(ThetaConfig.IDENTITY)
        val testResult = harness.evaluateOnTest(ThetaConfig.IDENTITY)
        println("BASELINE val:  hitRate=%.4f coverage=%d".format(valResult.hitRate, valResult.coverage))
        println("BASELINE test: hitRate=%.4f coverage=%d".format(testResult.hitRate, testResult.coverage))
        assertTrue(valResult.coverage > 0)
        assertTrue(testResult.coverage > 0)
    }

    /** 第一次调优：v1 11 参数 · NelderMead maxIter=200 · 已完成，保留为参考 */
    @Test
    fun `first tuning run NelderMead v1 DONE`() {
        // v1 结果已存档 —— train=89.68% val=86.63% test=82.77%
        // 保留此测试以验证向后兼容性
    }

    /** 第二次调优：v2 9 参数 + D 树合并 + 放宽边界 · NelderMead maxIter=400 · 已完成 */
    @Test
    fun `second tuning run NelderMead v2 DONE`() {
        // v2 结果已存档 —— train=92.26% val=85.15% test=87.16%
    }

    /**
     * 以「次日 a1 市值加权涨跌幅双向方向」为标的，评估生产 θ 的真实预测力。
     * 主轴：多头侧命中 vs 天然上行率、空头侧命中 vs 天然下行率，各自净增益与单尾 p。
     */
    @Test
    fun `production theta vs next-day market direction both sides`() {
        val harness = SentimentTuningHarness()
        val theta = ThetaConfig.PRODUCTION
        fun report(tag: String, r: MarketEvalResult) {
            fun pct(x: Double) = if (x.isNaN()) "  n/a" else "%5.1f%%".format(x * 100)
            fun p(x: Double) = if (x.isNaN()) "n/a" else "%.4f".format(x)
            println("===== $tag (n=${r.coverage}) =====")
            println("  天然基准: 上行 ${pct(r.baseUp)} / 下行 ${pct(r.baseDown)}")
            println("  多头侧 [score>0.5]: 共 ${r.longDays} 天, 次日真涨 ${pct(r.longHit)} (基准 ${pct(r.baseUp)}, 净增益 ${pct(r.longHit - r.baseUp)}, 单尾p=${p(r.longP)})")
            println("  空头侧 [score<0.5]: 共 ${r.shortDays} 天, 次日真跌 ${pct(r.shortHit)} (基准 ${pct(r.baseDown)}, 净增益 ${pct(r.shortHit - r.baseDown)}, 单尾p=${p(r.shortP)})")
            println("  IC: Pearson=${p(r.pearson)} Spearman=${p(r.spearman)}")
            println("  五档次日a1均值(低→高score): " + r.quintileReturns.joinToString(" ") { "%+.4f".format(it) })
        }
        report("TRAIN 2020-2023", harness.evaluateVsMarket(theta, harness.trainRecordsForMarket()))
        report("VAL   2024",      harness.evaluateVsMarket(theta, harness.valRecordsForMarket()))
        report("TEST  2025+",     harness.evaluateVsMarket(theta, harness.testRecordsForMarket()))
    }

    /**
     * 双向调优 m1：目标函数换成 max-min（两侧命中率最小值），用次日 a1 方向作标的。
     * 目标：让 test 集多头/空头双向命中率都 > 80%。
     */
    @Test
    fun `bidirectional tuning m1 maxmin NelderMead`() {
        val harness = SentimentTuningHarness()
        val space = harness.buildSearchSpace()
        val objective = harness.objectiveVsMarket()
        val workspace = Files.createTempDirectory("sentiment-bidir-m1-")
        val ctx = org.shiroumi.strategy.research.pipeline.ResearchContext(
            runId = "bidir-m1-${UUID.randomUUID().toString().take(8)}",
            startDate = LocalDate(2020, 1, 2),
            endDate = LocalDate(2023, 12, 29),
            params = emptyMap(),
            workspace = workspace,
            randomSeed = 202L,
        )
        val optimizer = NelderMeadOptimizer(
            space = space, objective = objective,
            budget = TuningBudget(maxIter = 800, patience = 100, minDelta = 1e-6),
        )
        val result = optimizer.optimize(ctx)
        assertNotNull(result.best)
        val theta = ThetaConfig.fromParams(result.best.params)
        println("M1 TUNED theta: $theta")
        println("M1 train objective=%.4f stop=%s".format(result.best.observation.score, result.stopReason))
        fun report(tag: String, r: MarketEvalResult) {
            fun pct(x: Double) = if (x.isNaN()) "n/a" else "%5.1f%%".format(x * 100)
            val ic = if (r.spearman.isNaN()) "n/a" else "%.3f".format(r.spearman)
            println("  $tag (n=${r.coverage}): 多头 ${pct(r.longHit)}[${r.longDays}d] / 空头 ${pct(r.shortHit)}[${r.shortDays}d] / minHit=${pct(minOf(r.longHit, r.shortHit))} / IC=$ic")
        }
        report("TRAIN", harness.evaluateVsMarket(theta, harness.trainRecordsForMarket()))
        report("VAL  ", harness.evaluateVsMarket(theta, harness.valRecordsForMarket()))
        report("TEST ", harness.evaluateVsMarket(theta, harness.testRecordsForMarket()))
        println("M1 written: ${result.writeTo(ctx)}")
    }

    /**
     * 双向调优 m2：train+val 联合 max-min，压制 m1 的 test 空头泛化回落。
     */
    @Test
    fun `bidirectional tuning m2 joint maxmin`() {
        val harness = SentimentTuningHarness()
        val space = harness.buildSearchSpace()
        val objective = harness.objectiveVsMarketJoint()
        val workspace = Files.createTempDirectory("sentiment-bidir-m2-")
        val ctx = org.shiroumi.strategy.research.pipeline.ResearchContext(
            runId = "bidir-m2-${UUID.randomUUID().toString().take(8)}",
            startDate = LocalDate(2020, 1, 2),
            endDate = LocalDate(2024, 12, 31),
            params = emptyMap(),
            workspace = workspace,
            randomSeed = 303L,
        )
        val optimizer = NelderMeadOptimizer(
            space = space, objective = objective,
            budget = TuningBudget(maxIter = 1000, patience = 120, minDelta = 1e-6),
        )
        val result = optimizer.optimize(ctx)
        assertNotNull(result.best)
        val theta = ThetaConfig.fromParams(result.best.params)
        println("M2 TUNED theta: $theta")
        println("M2 train objective=%.4f stop=%s".format(result.best.observation.score, result.stopReason))
        fun report(tag: String, r: MarketEvalResult) {
            fun pct(x: Double) = if (x.isNaN()) "n/a" else "%5.1f%%".format(x * 100)
            val ic = if (r.spearman.isNaN()) "n/a" else "%.3f".format(r.spearman)
            println("  $tag (n=${r.coverage}): 多头 ${pct(r.longHit)}[${r.longDays}d] / 空头 ${pct(r.shortHit)}[${r.shortDays}d] / minHit=${pct(minOf(r.longHit, r.shortHit))} / IC=$ic")
        }
        report("TRAIN", harness.evaluateVsMarket(theta, harness.trainRecordsForMarket()))
        report("VAL  ", harness.evaluateVsMarket(theta, harness.valRecordsForMarket()))
        report("TEST ", harness.evaluateVsMarket(theta, harness.testRecordsForMarket()))
        println("M2 written: ${result.writeTo(ctx)}")
    }

    /**
     * 双向调优 m3：固定 m1 最优 θ，在 VAL 集上扫描看跌阈值 τ_short，
     * 用更严格的看跌门槛换空头精度；选定后在 TEST 集验收。τ_long 固定 0.5。
     */
    @Test
    fun `bidirectional tuning m3 threshold scan`() {
        val harness = SentimentTuningHarness()
        // 生产 θ（= m1 双向最优）
        val theta = ThetaConfig.PRODUCTION
        fun pct(x: Double) = if (x.isNaN()) "  n/a" else "%5.1f%%".format(x * 100)
        fun f2(x: Double) = "%.2f".format(x)
        val tauLong = 0.50
        println("===== m3 在 VAL 集扫描 τ_short（τ_long=0.50 固定）=====")
        var bestTau = 0.50; var bestValShort = -1.0
        for (i in 0..9) {
            val tauShort = 0.50 - i * 0.04   // 0.50 → 0.14
            val v = harness.evaluateVsMarketDual(theta, harness.valRecordsForMarket(), tauLong, tauShort)
            println("  τ_short=${f2(tauShort)} → VAL 多头 ${pct(v.longHit)}[${v.longDays}d] 空头 ${pct(v.shortHit)}[${v.shortDays}d] 弃权 ${v.abstain}")
            // 选「空头>=80% 且 空头样本>=30」里空头率最高的
            if (!v.shortHit.isNaN() && v.shortHit >= 0.80 && v.shortDays >= 30 && v.shortHit > bestValShort) {
                bestValShort = v.shortHit; bestTau = tauShort
            }
        }
        println(">>> VAL 选定 τ_short=${f2(bestTau)} (val空头=${pct(bestValShort)})")
        println("===== TEST 集验收（τ_long=0.50, τ_short=${f2(bestTau)}）=====")
        val t = harness.evaluateVsMarketDual(theta, harness.testRecordsForMarket(), tauLong, bestTau)
        println("  TEST 多头 ${pct(t.longHit)}[${t.longDays}d] / 空头 ${pct(t.shortHit)}[${t.shortDays}d] / 弃权 ${t.abstain} / 覆盖 ${t.coverage}")
    }

    /** 第三次调优：v3 继续放宽 θτ 到 (-6, 6) · NelderMead maxIter=600 */
    @Test
    fun `third tuning run NelderMead v3`() {
        val harness = SentimentTuningHarness()
        val space = harness.buildSearchSpace()
        val objective = harness.objective()
        val workspace = Files.createTempDirectory("sentiment-tune-v3-")
        val ctx = org.shiroumi.strategy.research.pipeline.ResearchContext(
            runId = "v3-${UUID.randomUUID().toString().take(8)}",
            startDate = LocalDate(2020, 1, 2),
            endDate = LocalDate(2023, 12, 29),
            params = emptyMap(),
            workspace = workspace,
            randomSeed = 126L,
        )
        val optimizer = NelderMeadOptimizer(
            space = space, objective = objective,
            budget = TuningBudget(maxIter = 600, patience = 80, minDelta = 1e-6),
        )
        val result = optimizer.optimize(ctx)
        assertNotNull(result.best)
        val bestTheta = ThetaConfig.fromParams(result.best.params)
        println("V3 TUNED theta: $bestTheta")
        println("V3 BEST train: %.4f stop=%s".format(result.best.observation.score, result.stopReason))
        val v = harness.evaluateOnVal(bestTheta)
        val t = harness.evaluateOnTest(bestTheta)
        println("V3 val:  hit=%.4f cov=%d".format(v.hitRate, v.coverage))
        println("V3 test: hit=%.4f cov=%d".format(t.hitRate, t.coverage))
        println("V3 written: ${result.writeTo(ctx)}")
    }
}

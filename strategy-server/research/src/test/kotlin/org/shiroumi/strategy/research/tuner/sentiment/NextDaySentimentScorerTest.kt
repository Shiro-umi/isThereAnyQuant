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

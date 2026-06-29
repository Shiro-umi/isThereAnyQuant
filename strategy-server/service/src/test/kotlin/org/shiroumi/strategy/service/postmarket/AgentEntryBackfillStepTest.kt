package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.shiroumi.agententry.BackfillResult
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AgentEntryBackfillStep] 纯逻辑测试——注入 selection fixture 与 fake 单只回填器，
 * 不碰真实 agent 子进程，只验证买点校验、顺位补充、已有买点跳过、候选为空、阈值判定边界。
 *
 * 股票代码使用不存在的虚拟代码（TST 前缀），确保 [AgentEntryBackfillStep.loadClosePriceMap]
 * 查不到真实收盘价，买点校验走"无收盘价→放行"分支，测试只验证编排逻辑不依赖真实行情。
 */
class AgentEntryBackfillStepTest {

    private val signalDate = LocalDate(2026, 6, 18)
    private val targetDate = LocalDate(2026, 6, 22)

    private fun selection(tsCode: String, score: Double, limit: Double? = null) =
        ProfitPredictionSelection(
            tradeDate = signalDate,
            targetDate = targetDate,
            tsCode = tsCode,
            modelScore = score,
            selected = true,
            targetWeight = 1.0 / 3,
            sentimentExposure = 0.0,
            selectionReason = null,
            modelId = "test",
            candidateMode = "all-universe",
            limitPrice = limit,
        )

    private val config = AgentEntryBackfillConfig(
        enabled = true,
        minCoverage = 1.0,
        topN = 3,
        parallelism = 3,
        perStockTimeoutSec = 300,
        modelKey = null,
    )

    // 不存在于 DB 中的虚拟代码，确保 closePriceMap 查不到 → 校验放行
    private val c1 = "TST001.SH"
    private val c2 = "TST002.SH"
    private val c3 = "TST003.SZ"
    private val c4 = "TST004.SZ"
    private val c5 = "TST005.SH"
    private val c6 = "TST006.SZ"

    @Test
    fun `全部成功 覆盖率为1`() = runTest {
        val selections = listOf(
            selection(c1, 0.99),
            selection(c2, 0.98),
            selection(c3, 0.97),
        )
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config,
            loadSelections = { selections },
            backfillOne = { _, _, _ -> BackfillResult(ok = true, limitPrice = 60.0) },
        )
        assertEquals(3, outcome.candidates)
        assertEquals(3, outcome.attempted)
        assertEquals(3, outcome.filled)
        assertEquals(1.0, outcome.coverage)
    }

    @Test
    fun `单只失败 覆盖率不足1`() = runTest {
        val selections = listOf(
            selection(c1, 0.99),
            selection(c2, 0.98),
            selection(c3, 0.97),
        )
        // 第三只识别不出买点（agent 失败/无产出）。
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config,
            loadSelections = { selections },
            backfillOne = { _, _, code ->
                BackfillResult(ok = code != c3, limitPrice = if (code != c3) 60.0 else null)
            },
        )
        assertEquals(3, outcome.candidates)
        assertEquals(2, outcome.filled)
        assertTrue(outcome.coverage < 1.0)
        assertEquals(2.0 / 3, outcome.coverage)
    }

    @Test
    fun `已有买点的票跳过不重复跑`() = runTest {
        var ranCodes = mutableListOf<String>()
        val selections = listOf(
            selection(c1, 0.99, limit = 60.0), // 已有买点
            selection(c2, 0.98),
            selection(c3, 0.97),
        )
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config,
            loadSelections = { selections },
            backfillOne = { _, _, code ->
                synchronized(ranCodes) { ranCodes.add(code) }
                BackfillResult(ok = true, limitPrice = 60.0)
            },
        )
        // 已有买点的 c1 不进 agent，但计入 filled。
        assertEquals(3, outcome.candidates)
        assertEquals(2, outcome.attempted)
        assertEquals(3, outcome.filled)
        assertEquals(1.0, outcome.coverage)
        assertTrue(c1 !in ranCodes)
        assertEquals(setOf(c2, c3), ranCodes.toSet())
    }

    @Test
    fun `候选为空 覆盖率视为1不阻断`() = runTest {
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config,
            loadSelections = { emptyList() },
            backfillOne = { _, _, _ -> error("不应被调用") },
        )
        assertEquals(0, outcome.candidates)
        assertEquals(0, outcome.filled)
        assertEquals(1.0, outcome.coverage)
    }

    @Test
    fun `topN 截断且无补充 前N只全有效`() = runTest {
        // 候选 6 只，topN=5 → 前 5 只全有效，第 6 只不触发补充。
        val selections = listOf(c1, c2, c3, c4, c5, c6).mapIndexed { i, code ->
            selection(code, 1.0 - i * 0.01)
        }
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config.copy(topN = 5),
            loadSelections = { selections },
            backfillOne = { _, _, _ -> BackfillResult(ok = true, limitPrice = 60.0) },
        )
        assertEquals(5, outcome.candidates)
        assertEquals(5, outcome.filled)
        assertEquals(1.0, outcome.coverage)
    }

    @Test
    fun `异常单只记为失败不影响其他`() = runTest {
        val selections = listOf(
            selection(c1, 0.99),
            selection(c2, 0.98),
        )
        val outcome = AgentEntryBackfillStep.backfill(
            targetDate = targetDate,
            config = config,
            loadSelections = { selections },
            backfillOne = { _, _, code ->
                if (code == c1) throw RuntimeException("agent 崩了")
                else BackfillResult(ok = true, limitPrice = 60.0)
            },
        )
        // candidates = min(topN=3, pool=2) = 2；c1 异常失败、c2 成功，无更多候选补充。
        assertEquals(2, outcome.candidates)
        assertEquals(1, outcome.filled)
        assertEquals(0.5, outcome.coverage)
    }

    @Test
    fun `配置默认值 全自动且覆盖率1`() {
        // 清掉可能残留的系统属性，验证缺省装配。
        listOf("enabled", "minCoverage", "topN", "parallelism", "perStockTimeoutSec", "modelKey")
            .forEach { System.clearProperty("quant.strategy.entryBackfill.$it") }
        val cfg = AgentEntryBackfillConfig.fromSystemProperties()
        assertTrue(cfg.enabled)
        assertEquals(1.0, cfg.minCoverage)
        assertEquals(5, cfg.topN)
        assertEquals(5, cfg.parallelism)
        assertEquals(300L, cfg.perStockTimeoutSec)
        assertEquals("deepseek-v4-pro", cfg.modelKey)
    }

    @Test
    fun `配置覆盖 系统属性生效`() {
        System.setProperty("quant.strategy.entryBackfill.enabled", "false")
        System.setProperty("quant.strategy.entryBackfill.minCoverage", "0.67")
        System.setProperty("quant.strategy.entryBackfill.topN", "5")
        try {
            val cfg = AgentEntryBackfillConfig.fromSystemProperties()
            assertTrue(!cfg.enabled)
            assertEquals(0.67, cfg.minCoverage)
            assertEquals(5, cfg.topN)
        } finally {
            listOf("enabled", "minCoverage", "topN")
                .forEach { System.clearProperty("quant.strategy.entryBackfill.$it") }
        }
    }

    @Test
    fun `minCoverage 越界被钳到0到1`() {
        System.setProperty("quant.strategy.entryBackfill.minCoverage", "1.5")
        try {
            assertEquals(1.0, AgentEntryBackfillConfig.fromSystemProperties().minCoverage)
        } finally {
            System.clearProperty("quant.strategy.entryBackfill.minCoverage")
        }
    }
}

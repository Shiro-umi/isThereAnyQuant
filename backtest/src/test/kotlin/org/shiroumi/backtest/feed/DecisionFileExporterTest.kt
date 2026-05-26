package org.shiroumi.backtest.feed

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.StrategyDecision

class DecisionFileExporterTest {

    @Test fun `重新导出会清空既有 decisions 文件，防止过期决策残留`() {
        val dir = Files.createTempDirectory("bt-decisions-clear")
        // 模拟上一轮 run 留下的旧文件（在本次导出区间之外）
        val staleFile = dir.resolve("2024-01-31.json")
        Files.writeString(staleFile, "{\"formatVersion\":1,\"executionDate\":\"2024-01-31\",\"decisions\":[]}")
        assertTrue(Files.exists(staleFile))

        val newDate = LocalDate(2024, 1, 2)
        val feed = InMemoryDecisionFeed(
            listOf(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = newDate,
                    reason = "test",
                    targetWeights = mapOf("000001.SZ" to 1.0),
                    sentimentExposure = 1.0,
                )
            )
        )
        val result = DecisionFileExporter(decisionsDir = dir, feed = feed).exportRange(listOf(newDate))

        assertFalse(Files.exists(staleFile), "旧的 decisions 文件必须被清理")
        assertTrue(Files.exists(dir.resolve("2024-01-02.json")), "新决策文件必须写入")
        assertEquals(1, result.writtenDays)
        assertEquals(0, result.emptyDays)
        assertEquals(1, result.totalDecisions)
    }

    @Test fun `当日策略无决策时不写文件，且不影响其他日期`() {
        val dir = Files.createTempDirectory("bt-decisions-skip-empty")
        val emptyDate = LocalDate(2024, 1, 2)
        val feed = InMemoryDecisionFeed(emptyList())
        val result = DecisionFileExporter(decisionsDir = dir, feed = feed).exportRange(listOf(emptyDate))

        assertFalse(Files.exists(dir.resolve("$emptyDate.json")))
        assertEquals(0, result.writtenDays)
        assertEquals(1, result.emptyDays)
    }
}

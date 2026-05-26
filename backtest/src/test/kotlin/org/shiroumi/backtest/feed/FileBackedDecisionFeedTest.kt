package org.shiroumi.backtest.feed

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision

class FileBackedDecisionFeedTest {

    @Test fun `文件不存在时返回空决策序列`() {
        val dir = Files.createTempDirectory("bt-decisions-empty")
        val feed = FileBackedDecisionFeed(dir)
        assertEquals(emptyList(), feed.decisionsFor(LocalDate(2024, 1, 2)))
    }

    @Test fun `DecisionFile 与 FileBackedDecisionFeed 可以 round-trip 两种决策子类型`() {
        val dir = Files.createTempDirectory("bt-decisions-roundtrip")
        val date = LocalDate(2024, 1, 2)
        val original = listOf<StrategyDecision>(
            StrategyDecision.TargetPortfolioDecision(
                effectiveDate = date,
                reason = "test target portfolio",
                targetWeights = mapOf("000001.SZ" to 0.5, "600519.SH" to 0.5),
                sentimentExposure = 1.0,
            ),
            StrategyDecision.TradeIntentDecision(
                effectiveDate = date,
                reason = "test trade intent",
                tsCode = "300750.SZ",
                side = Side.SELL,
                weight = 0.0,
                hint = ExecutionHint.OPEN,
            ),
        )

        // 写文件，与 DecisionFileExporter 路径保持一致。
        val payload = DecisionFile(executionDate = date, decisions = original)
        Files.writeString(dir.resolve("$date.json"), DecisionFileJson.encodeToString(payload))

        val replayed = FileBackedDecisionFeed(dir).decisionsFor(date)
        assertEquals(original.size, replayed.size)
        assertTrue(replayed[0] is StrategyDecision.TargetPortfolioDecision)
        assertTrue(replayed[1] is StrategyDecision.TradeIntentDecision)
        assertEquals(original, replayed)
    }
}

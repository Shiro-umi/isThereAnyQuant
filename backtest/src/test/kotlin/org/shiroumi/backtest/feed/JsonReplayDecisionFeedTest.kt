package org.shiroumi.backtest.feed

import java.nio.file.Files
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonReplayDecisionFeedTest {

    @Test fun `从 JSON 文件回放策略决策`() {
        val path = Files.createTempFile("backtest-decisions", ".json")
        Files.writeString(
            path,
            """
            [
              {
                "type": "target-portfolio",
                "effectiveDate": "2024-01-03",
                "reason": "json replay",
                "targetWeights": { "000001.SZ": 0.2 },
                "sentimentExposure": 0.2
              }
            ]
            """.trimIndent(),
        )

        val decision = JsonReplayDecisionFeed(path).decisionsFor(T1).single() as StrategyDecision.TargetPortfolioDecision

        assertEquals(mapOf("000001.SZ" to 0.2), decision.targetWeights)
        Files.deleteIfExists(path)
    }
}

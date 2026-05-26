package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PostMarketOrchestrator 的最小回归测试。
 *
 * 21 步算法链等价性由 [PostMarketPreparationJob] 自身的算法回归测试和
 * 复用的 [TargetPortfolioGeneratorTest] / [SentimentRuntimeSeedBuilderTest] 等保证。
 *
 * owner 切换路径由 [org.shiroumi.strategy.service.runtime.PostMarketStrategyRuntimeTest] 覆盖。
 *
 * 这里只验证 orchestrator 自身在空输入下的短路行为。带 fixture 的端到端集成测试归入下次清理批次。
 */
class PostMarketOrchestratorTest {

    @Test
    fun `executeTradeDatesCatching with empty list short-circuits without touching repositories`() = runTest {
        val result = PostMarketOrchestrator.executeTradeDatesCatching(emptyList())

        assertEquals(emptyList(), result.processedDates)
        assertNull(result.failedDate)
        assertNull(result.failure)
    }

    @Test
    fun `executeTradeDates with empty list returns success without throwing`() = runTest {
        val result = PostMarketOrchestrator.executeTradeDates(emptyList())

        assertEquals(emptyList(), result.processedDates)
        assertNull(result.failedDate)
    }

    @Test
    fun `executePendingDailyTasksCatching with empty pending short-circuits`() = runTest {
        val result = PostMarketOrchestrator.executePendingDailyTasksCatching(
            today = kotlinx.datetime.LocalDate(2099, 12, 31)
        )

        assertEquals(emptyList(), result.processedDates)
        assertNull(result.failedDate)
        assertNull(result.failure)
    }
}

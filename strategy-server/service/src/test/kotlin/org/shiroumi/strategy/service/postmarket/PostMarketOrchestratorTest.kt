package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PostMarketOrchestrator 的最小回归测试。
 *
 * 盘后算法链由 [PostMarketPreparationJob] 自身的算法回归测试和
 * [org.shiroumi.strategy.core.daily.seed.SentimentRuntimeSeedBuilder] 等保证。
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

    @Test
    @kotlin.test.Ignore
    // 手动回填驱动器：直连生产数据库与推理服务端口，会重写窗口内策略表。
    // 仅手动去掉 Ignore 后单独执行；常规测试套件不运行（端口被部署族占用时模型身份守卫会拒绝）。
    fun runActualBackfill() = runBlocking {
        org.shiroumi.config.ConfigManager.load()
        val start = kotlinx.datetime.LocalDate(2026, 5, 4)
        val end = kotlinx.datetime.LocalDate(2026, 6, 5)
        val dates = org.shiroumi.database.common.repository.TradingCalendarRepository.findOpenDates(start, end)
        println("Rebuilding strategy for dates: $dates")
        val result = PostMarketOrchestrator.executeTradeDates(dates)
        println("Rebuild complete. Processed dates: ${result.processedDates}")
    }
}

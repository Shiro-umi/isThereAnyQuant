package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.LocalDate

/**
 * 盘后策略运行时端口。
 *
 * 将 [PostMarketStrategyRuntime] 的核心能力抽象为接口，
 * 使上层 command handler 和测试可以解耦依赖具体实现。
 */
interface PostMarketRuntime {
    suspend fun rebuildDate(tradeDate: LocalDate, reason: String?): PostMarketRebuildResult

    suspend fun rebuildRange(
        startDate: LocalDate,
        endDate: LocalDate,
        reason: String?,
    ): PostMarketRebuildResult
}

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

    /**
     * 强制重发布最新内存快照：不重算，只从 DB 重读最新落库审计与持仓，
     * 重新发布 POSITIONS / POSITION_TRACKING 快照。
     *
     * 用于外部直接改库（重刷持仓、回填买点、离线脚本落库等）后让在跑 service 的内存快照
     * 追平已落库状态，无需重启。与 [rebuildDate] 区分——后者会重算整条盘后链路；本方法是纯重读重发布。
     */
    suspend fun publishLatestPositions(reason: String): PostMarketPositionPublishResult
}

package org.shiroumi.strategy.service.runtime

/**
 * 盘中策略运行时端口。
 *
 * 将 [IntradayStrategyRuntime] 的核心能力抽象为接口，
 * 使上层 command handler 和测试可以解耦依赖具体实现。
 */
interface IntradayRuntime {
    suspend fun refresh(reason: String): IntradayRefreshResult
}

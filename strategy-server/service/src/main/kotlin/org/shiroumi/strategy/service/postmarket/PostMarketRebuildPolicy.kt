package org.shiroumi.strategy.service.postmarket

import model.PriceBasis

/**
 * 盘后 rebuild 政策。封装窗口/并发/精度参数。
 *
 * 历史窗口默认 700 天用于保证因子滚动状态预热充分。
 */
data class PostMarketRebuildPolicy(
    val historyLookbackDays: Long = 700,
    val pendingDailyTaskWindow: Int = 120,
    val requiredHistory: Int = 400,
    val signalBasis: PriceBasis = PriceBasis.HFQ,
    val chunkSize: Int = 300,
    val parallelism: Int? = null
) {
    companion object {
        fun default(): PostMarketRebuildPolicy = PostMarketRebuildPolicy()
    }
}

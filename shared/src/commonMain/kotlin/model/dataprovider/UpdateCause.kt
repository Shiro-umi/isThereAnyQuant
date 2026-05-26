package model.dataprovider

import kotlinx.serialization.Serializable

/**
 * DataProvider 更新触发原因。
 *
 * 设计目的：
 * 1. 显式记录“为什么发生这次刷新”
 * 2. 让调度层、日志层、监控层能够区分更新来源
 * 3. 避免以后所有刷新都变成语义不清晰的 `refresh()`
 */
@Serializable
enum class UpdateCause {
    SERVER_BOOTSTRAP,
    OFF_MARKET_SYNC,
    POST_MARKET_DATA_READY,
    TRADING_TICK,
    TRADING_BREAK_RECALIBRATE,
    MANUAL_REFRESH,
    SUBSCRIBER_WARMUP
}

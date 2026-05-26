package model.dataprovider

import kotlinx.serialization.Serializable

/**
 * DataProvider 运行阶段。
 *
 * 与旧的“市场状态”不同，这里是面向数据更新策略的执行语义：
 * - OFF_MARKET：允许历史同步、落库、刷新 H 窗口
 * - TRADING_ACTIVE：允许高频更新 R 窗口
 * - TRADING_BREAK：只允许午休校准，不做持续轮询
 */
@Serializable
enum class ExecutionPhase {
    OFF_MARKET,
    TRADING_ACTIVE,
    TRADING_BREAK
}

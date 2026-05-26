package org.shiroumi.server

import org.shiroumi.database.strategy.daily.resetStrategyFlags

/**
 * 清理策略计算 flag：将 calendar 表中 strategy_updated 字段全部重置为 0，
 * 使下次运行 runDataUpdate 时重新计算所有情绪指标。
 */
fun main() {
    val count = resetStrategyFlags()
    println("✅ 已重置 $count 条记录的 strategy_updated 标记为 0。")
    println("   下次运行 runDataUpdate 将重新计算情绪指标。")
}

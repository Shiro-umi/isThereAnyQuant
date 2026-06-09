package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * 生产盘后持仓状态快照表。
 *
 * 语义：某 `trade_date` 收盘后「仍在持有」的快照（每日重写），
 * 附带止盈止损退出判定所需的入场元数据：
 * - `entry_date`：实际买入日（选股日的次一交易日，T+1）
 * - `entry_price`：买入价（买入日开盘价）
 * - `signal_date_low`：信号日（选股日，T 日）最低价，用于价格止损
 *
 * 与回测 PositionExitManager.EntryMeta 语义对齐，但生产侧不引入账户/现金/qty。
 */
object DailyStrategyHoldingTable : Table(name = "daily_strategy_holding") {
    val tradeDate = date("trade_date")
    val tsCode = varchar("ts_code", 15)
    val entryDate = date("entry_date")
    val entryPrice = double("entry_price")
    val signalDateLow = double("signal_date_low")

    init {
        uniqueIndex("uk_daily_strategy_holding", tradeDate, tsCode)
        index("idx_daily_strategy_holding_trade", false, tradeDate)
    }
}

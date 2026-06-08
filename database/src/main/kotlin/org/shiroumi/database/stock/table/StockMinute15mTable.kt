package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * 股票 15 分钟历史 K 线表。
 *
 * 数据源：Tushare `stk_mins` freq=15min。`tradeTime` 保留原始时间戳，`tradeDate`
 * 派生自 `tradeTime` 前 10 位，用于研究层按交易日批量读取开盘/尾盘结构。
 */
object StockMinute15mTable : Table(name = "stock_minute_15m") {
    val tsCode = varchar("ts_code", 15).index()
    val tradeDate = date("trade_date").index()
    val tradeTime = varchar("trade_time", 25)
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val vol = float("vol")          // stk_mins 单位：手
    val amount = float("amount")    // 成交额
    val updatedAtMillis = long("updated_at_millis")

    init {
        uniqueIndex("uk_stock_15m_code_time", tsCode, tradeTime)
        index("idx_stock_15m_date_code", false, tradeDate, tsCode)
        index("idx_stock_15m_code_date", false, tsCode, tradeDate)
    }
}

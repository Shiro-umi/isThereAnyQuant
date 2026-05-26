package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * 统一的股票日线行情表 (替代之前的分表模式)
 */
object StockDailyDataTable : Table(name = "stock_daily_data") {
    val tsCode = varchar("ts_code", 15).index()
    val tradeDate = date("trade_date").index()
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val adj = float("adj")
    val openQfq = float("open_qfq").default(0f)
    val closeQfq = float("close_qfq").default(0f)
    val highQfq = float("high_qfq").default(0f)
    val lowQfq = float("low_qfq").default(0f)
    val volume = float("volume")
    val volumeQfq = float("volume_qfq").default(0f)
    val turnoverReal = float("turnover_real")
    val pe = float("pe")
    val peTtm = float("pe_ttm")
    val pb = float("pb")
    val ps = float("ps")
    val psTtm = float("ps_ttm")
    val mvTotal = float("mv_total")
    val mvCirc = float("mv_circ")

    init {
        // 对应迁移脚本中的唯一约束
        uniqueIndex("uk_code_date", tsCode, tradeDate)
        index("idx_stock_daily_date_code", false, tradeDate, tsCode)
    }
}

// 为了兼容性保留一个空的抽象类定义（如果需要），但建议后续全部切换到 StockDailyDataTable
@Deprecated("Use StockDailyDataTable instead")
abstract class StockCandleTable(tsCode: String) : Table(name = "stock_daily_data")

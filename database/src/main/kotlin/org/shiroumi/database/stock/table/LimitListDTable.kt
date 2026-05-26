package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object LimitListDTable : Table(name = "tushare_limit_list_d") {
    val tradeDate = date("trade_date").index()
    val tsCode = varchar("ts_code", 15).index()
    val industry = varchar("industry", 64).nullable()
    val name = varchar("name", 64).nullable()
    val close = double("close").nullable()
    val pctChg = double("pct_chg").nullable()
    val amount = double("amount").nullable()
    val limitAmount = double("limit_amount").nullable()
    val floatMv = double("float_mv").nullable()
    val totalMv = double("total_mv").nullable()
    val turnoverRatio = double("turnover_ratio").nullable()
    val fdAmount = double("fd_amount").nullable()
    val firstTime = varchar("first_time", 16).nullable()
    val lastTime = varchar("last_time", 16).nullable()
    val openTimes = integer("open_times").nullable()
    val upStat = varchar("up_stat", 16).nullable()
    val limitTimes = integer("limit_times").nullable()
    val limitType = varchar("limit_type", 2)
    val updatedAtMillis = long("updated_at_millis")

    init {
        uniqueIndex("uk_limit_list_d_date_code_type", tradeDate, tsCode, limitType)
        index("idx_limit_list_d_code_date", false, tsCode, tradeDate)
        index("idx_limit_list_d_date_type", false, tradeDate, limitType)
    }
}

package org.shiroumi.database.sw_index.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.MAX_VARCHAR_LENGTH

abstract class SwIndexDailyTable(tsCode: String) : UUIDTable(name = tsCode) {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH).uniqueIndex(
        "${tsCode.split(".").first().replace("`", "")}_trade_date_unique"
    )
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val change = float("change")
    val pctChange = float("pct_change")
    val vol = float("vol")
    val amount = float("amount")
    val pe = float("pe")
    val pb = float("pb")
    val floatMv = float("float_mv")
    val totalMv = float("total_mv")
}
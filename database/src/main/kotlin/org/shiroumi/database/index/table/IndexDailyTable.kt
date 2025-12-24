package org.shiroumi.database.index.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.MAX_VARCHAR_LENGTH

abstract class IndexDailyTable(tsCode: String) : UUIDTable(name = tsCode) {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH).uniqueIndex(
        "${tsCode.replace(".", "_").replace("`", "").lowercase()}_trade_date_unique"
    )
    val close = float("close")
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val preClose = float("pre_close")
    val change = float("change")
    val pctChg = float("pct_chg")
    val vol = float("vol")
    val amount = float("amount")
}

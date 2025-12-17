package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.date
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH


abstract class StockCandleTable(tsCode: String) : UUIDTable(name = tsCode) {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val date = date("trade_date").uniqueIndex(
        "${tsCode.split(".").first().replace("`", "")}_trade_date_unique"
    )
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val openQfq = float("open_qfq")
    val closeQfq = float("close_qfq")
    val highQfq = float("high_qfq")
    val lowQfq = float("low_qfq")
    val volume = float("volume")
    val volumeQfq = float("volume_qfq")
    val turnoverReal = float("turnover_rate_f")
    val pe = float("pe") // 市盈率
    val peTtm = float("pe_ttm") // 动态市盈率
    val pb = float("pb") // 市净率
    val ps = float("ps") // 市销率
    val psTtm = float("ps_ttm") // 动态市销率
    val mvTotal = float("total_mv") // 总市值
    val mvCirc = float("circ_mv") // 流通总市值
    val upLimit = float("up_limit")
    val downLimit = float("down_limit")
}
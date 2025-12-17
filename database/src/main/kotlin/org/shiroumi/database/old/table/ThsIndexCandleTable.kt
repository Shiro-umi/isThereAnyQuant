package org.shiroumi.database.old.table

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object ThsIndexCandleTable : IntIdTable("ths_index_candle") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val close = float("close")
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val preClose = float("pre_close")
    val avgPrice = float("avg_price")
    val change = float("change")
    val pctChange = float("pct_change")
    val vol = float("vol")
    val turnoverRate = float("turnover_rate")
    val totalMv = float("total_mv").nullable()
    val floatMv = float("float_mv").nullable()

    init {
        index(isUnique = false, tsCode, tradeDate)
    }
}


//class ThsIndexCandle(id: EntityID<Int>) : IntEntity(id) {
//
//    companion object : IntEntityClass<ThsIndexCandle>(ThsIndexCandleTable)
//
//    var tsCode by ThsIndexCandleTable.tsCode
//    var tradeDate by ThsIndexCandleTable.tradeDate
//    var close by ThsIndexCandleTable.close
//    var open by ThsIndexCandleTable.open
//    var high by ThsIndexCandleTable.high
//    var low by ThsIndexCandleTable.low
//    var preClose by ThsIndexCandleTable.preClose
//    var avgPrice by ThsIndexCandleTable.avgPrice
//    var change by ThsIndexCandleTable.change
//    var pctChange by ThsIndexCandleTable.pctChange
//    var vol by ThsIndexCandleTable.vol
//    var turnoverRate by ThsIndexCandleTable.turnoverRate
//    var totalMv by ThsIndexCandleTable.totalMv
//    var floatMv by ThsIndexCandleTable.floatMv
//}
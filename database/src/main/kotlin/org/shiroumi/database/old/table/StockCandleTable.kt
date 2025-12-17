package org.shiroumi.database.old.table

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object DailyCandleTable : IntIdTable("daily_candle") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val close = float("close").default(0f)
    val open = float("open").default(0f)
    val high = float("high").default(0f)
    val low = float("low").default(0f)
    val vol = float("vol").default(0f)

    init {
        index(isUnique = false, tsCode, tradeDate)
    }
}

//class Candle(id: EntityID<Int>) : IntEntity(id) {
//    companion object : IntEntityClass<Candle>(DailyCandleTable)
//
//    val tsCode by DailyCandleTable.tsCode
//    val tradeDate by DailyCandleTable.tradeDate
//    val close by DailyCandleTable.close
//    val open by DailyCandleTable.open
//    val high by DailyCandleTable.high
//    val low by DailyCandleTable.low
//    var vol by DailyCandleTable.vol
//}
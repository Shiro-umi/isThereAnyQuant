package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object DailyCandleTable : IntIdTable("daily_candle") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).index()
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH).index()
    val close = float("close").default(0f).index()
    val open = float("open").default(0f)
    val high = float("high").default(0f)
    val low = float("low").default(0f)
    val vol = float("vol").default(0f).index()
}

class Candle(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Candle>(DailyCandleTable)

    val tsCode by DailyCandleTable.tsCode
    val tradeDate by DailyCandleTable.tradeDate
    val close by DailyCandleTable.close
    val open by DailyCandleTable.open
    val high by DailyCandleTable.high
    val low by DailyCandleTable.low
    var vol by DailyCandleTable.vol
}
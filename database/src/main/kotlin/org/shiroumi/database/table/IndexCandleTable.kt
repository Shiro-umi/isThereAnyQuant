package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH


object IndexDailyCandleTable : IntIdTable("index_daily_candle") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val close = float("close").default(0f)
    val open = float("open").default(0f)
    val high = float("high").default(0f)
    val low = float("low").default(0f)
    val vol = float("vol").default(0f)
    val amount = float("amount").default(0f)

    init {
        index(isUnique = true, tsCode, tradeDate)
    }
}

class IndexCandle(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<IndexCandle>(IndexDailyCandleTable)

    val tsCode by IndexDailyCandleTable.tsCode
    val tradeDate by IndexDailyCandleTable.tradeDate
    val close by IndexDailyCandleTable.close
    val open by IndexDailyCandleTable.open
    val high by IndexDailyCandleTable.high
    val low by IndexDailyCandleTable.low
    val vol by IndexDailyCandleTable.vol
    val amount by IndexDailyCandleTable.amount
}
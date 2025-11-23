package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object SwIndexDailyCandleTable : IntIdTable("sw_index_daily_candle") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val change = float("change")
    val pctChange = float("pct_change")
    val vol = float("vol")
    val amount = float("amount")
    val pe = float("pe").nullable()
    val pb = float("pb").nullable()
    val floatMv = float("float_mv").nullable()
    val totalMv = float("total_mv").nullable()

    init {
        index(isUnique = true, tsCode, tradeDate)
    }
}

class SwIndexDailyCandle(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SwIndexDailyCandle>(SwIndexDailyCandleTable)

    var tsCode by SwIndexDailyCandleTable.tsCode
    var tradeDate by SwIndexDailyCandleTable.tradeDate
    var name by SwIndexDailyCandleTable.name
    var open by SwIndexDailyCandleTable.open
    var high by SwIndexDailyCandleTable.high
    var low by SwIndexDailyCandleTable.low
    var close by SwIndexDailyCandleTable.close
    var change by SwIndexDailyCandleTable.change
    var pctChange by SwIndexDailyCandleTable.pctChange
    var vol by SwIndexDailyCandleTable.vol
    var amount by SwIndexDailyCandleTable.amount
    var pe by SwIndexDailyCandleTable.pe
    var pb by SwIndexDailyCandleTable.pb
    var floatMv by SwIndexDailyCandleTable.floatMv
    var totalMv by SwIndexDailyCandleTable.totalMv
}

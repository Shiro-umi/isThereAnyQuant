package org.shiroumi.database.old.table

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object AdjCandleTable : IntIdTable("adj_daily_candle") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val closeQfq = float("close_qfq").default(0f)
    val openQfq = float("open_qfq").default(0f)
    val highQfq = float("high_qfq").default(0f)
    val lowQfq = float("low_qfq").default(0f)
    val volFq = float("vol_fq").default(0f)
    val closeHfq = float("close_hfq").default(0f)
    val openHfq = float("open_hfq").default(0f)
    val highHfq = float("high_hfq").default(0f)
    val lowHfq = float("low_hfq").default(0f)

    init {
        index(false, tsCode, tradeDate)
    }
}

//class AdjCandle(id: EntityID<Int>) : IntEntity(id) {
//    companion object : IntEntityClass<AdjCandle>(AdjCandleTable)
//
//    val tsCode by AdjCandleTable.tsCode
//    val tradeDate by AdjCandleTable.tradeDate
//    val closeQfq by AdjCandleTable.closeQfq
//    val openQfq by AdjCandleTable.openQfq
//    val highQfq by AdjCandleTable.highQfq
//    val lowQfq by AdjCandleTable.lowQfq
//    val closeHfq by AdjCandleTable.closeHfq
//    val openHfq by AdjCandleTable.openHfq
//    val highHfq by AdjCandleTable.highHfq
//    val lowHfq by AdjCandleTable.lowHfq
//    val volFq by AdjCandleTable.volFq
//}
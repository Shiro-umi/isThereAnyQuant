package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object AdjFactorTable : IntIdTable("adj_factor") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).references(DailyCandleTable.tsCode)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val adjFactor = float("adj_factor").default(0f)

    init {
        index(false, tsCode, tradeDate)
    }
}

class AdjFactor(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<AdjFactor>(AdjFactorTable)

    val tsCode by AdjFactorTable.tsCode
    val tradeDate by AdjFactorTable.tradeDate
    val adjFactor by AdjFactorTable.adjFactor
}
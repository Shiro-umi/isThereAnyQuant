package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object StockTable : IntIdTable("stock") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).uniqueIndex()
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val area = varchar("area", MAX_VARCHAR_LENGTH)
    val industry = varchar("industry", MAX_VARCHAR_LENGTH)
    val cnSpell = varchar("cn_spell", MAX_VARCHAR_LENGTH)
    val market = varchar("market", MAX_VARCHAR_LENGTH)
}

class Stock(id: EntityID<Int>) : IntEntity(id) {

    companion object : IntEntityClass<Stock>(StockTable)

    val tsCode by StockTable.tsCode
    val name by StockTable.name
    val area by StockTable.area
    val industry by StockTable.industry
    val cnSpell by StockTable.cnSpell
    val market by StockTable.market
}
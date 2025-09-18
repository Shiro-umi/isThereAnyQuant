package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH
import org.shiroumi.database.table.ThsIndexCandleTable.tradeDate

object ThsIndexTable : IntIdTable("ths_index") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val count = integer("count")
    val exchange = varchar("exchange", MAX_VARCHAR_LENGTH)
    val listDate = varchar("list_date", MAX_VARCHAR_LENGTH)
    val type = varchar("type", MAX_VARCHAR_LENGTH)

    init {
        index(isUnique = false, tsCode, tradeDate)
    }
}

class ThsIndex(id: EntityID<Int>) : IntEntity(id) {

    companion object : IntEntityClass<ThsIndex>(ThsIndexTable)

    val tsCode by ThsIndexTable.tsCode
    val name by ThsIndexTable.name
    val count by ThsIndexTable.count
    val exchange by ThsIndexTable.exchange
    val listDate by ThsIndexTable.listDate
    val type by ThsIndexTable.type
}

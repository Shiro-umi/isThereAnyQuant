package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.UUIDEntity
import org.jetbrains.exposed.v1.dao.UUIDEntityClass
import org.shiroumi.database.MAX_VARCHAR_LENGTH
import java.util.UUID
import kotlin.uuid.Uuid

object StrategyTable : UUIDTable("strategy") {

    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH)
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val tradeDate = varchar("trade_date", MAX_VARCHAR_LENGTH)
    val strategy = text("strategy")

    init {
        index(isUnique = false, id, tsCode, tradeDate)
    }
}

class Strategy(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Strategy>(StrategyTable)

    val tsCode by StrategyTable.tsCode
    val name by StrategyTable.name
    val tradeDate by StrategyTable.tradeDate
    val strategy by StrategyTable.strategy
}
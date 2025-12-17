package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object StockBasicTable : UUIDTable("stock_info") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).uniqueIndex()
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val cnSpell = varchar("cn_spell", MAX_VARCHAR_LENGTH)
    val market = varchar("market", MAX_VARCHAR_LENGTH)
    val listStatus = varchar("list_status", MAX_VARCHAR_LENGTH)
}
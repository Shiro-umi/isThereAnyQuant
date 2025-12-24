package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object IndexTable : UUIDTable(name = "index_basic") {
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).uniqueIndex()
    val name = varchar("name", MAX_VARCHAR_LENGTH)
    val market = varchar("market", MAX_VARCHAR_LENGTH)
    val publisher = varchar("publisher", MAX_VARCHAR_LENGTH)
    val category = varchar("category", MAX_VARCHAR_LENGTH).nullable()
    val baseDate = varchar("base_date", MAX_VARCHAR_LENGTH).nullable()
    val basePoint = float("base_point").nullable()
    val listDate = varchar("list_date", MAX_VARCHAR_LENGTH).nullable()
}

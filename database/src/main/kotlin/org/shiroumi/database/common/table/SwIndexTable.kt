package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object SwIndexTable : UUIDTable(name = "sw_index") {
    val indexCode = varchar("index_code", MAX_VARCHAR_LENGTH).uniqueIndex()
    val industryName = varchar("industry_name", MAX_VARCHAR_LENGTH)
    val industryCode = varchar("industry_code", MAX_VARCHAR_LENGTH).nullable()
    val level = varchar("level", MAX_VARCHAR_LENGTH)
}
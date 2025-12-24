package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.shiroumi.database.MAX_VARCHAR_LENGTH

// l1_code, l1_name, l2_code, l2_name, l3_code, l3_name, ts_code, name, in_date, out_date, is_new
object SwIndexMemberTable : UUIDTable(name = "sw_index_member") {
    val l1Code = varchar("l1_code", MAX_VARCHAR_LENGTH)
    val l1Name = varchar("l1_name", MAX_VARCHAR_LENGTH)
    val l2Code = varchar("l2_code", MAX_VARCHAR_LENGTH)
    val l2Name = varchar("l2_name", MAX_VARCHAR_LENGTH)
    val l3Code = varchar("l3_code", MAX_VARCHAR_LENGTH)
    val l3Name = varchar("l3_name", MAX_VARCHAR_LENGTH)
    val tsCode = varchar("ts_code", MAX_VARCHAR_LENGTH).uniqueIndex()
    val name = varchar("name", MAX_VARCHAR_LENGTH)
}
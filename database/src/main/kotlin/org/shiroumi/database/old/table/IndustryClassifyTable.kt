package org.shiroumi.database.old.table

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.shiroumi.database.old.MAX_VARCHAR_LENGTH

object IndustryClassifyTable : IntIdTable("industry_classify") {
    val indexCode = varchar("index_code", MAX_VARCHAR_LENGTH)
    val industryName = varchar("industry_name", MAX_VARCHAR_LENGTH)
    val parentCode = varchar("parent_code", MAX_VARCHAR_LENGTH).nullable()
    val level = varchar("level", MAX_VARCHAR_LENGTH)
    val industryCode = varchar("industry_code", MAX_VARCHAR_LENGTH).nullable()
    val isPub = varchar("is_pub", MAX_VARCHAR_LENGTH).nullable()
    val src = varchar("src", MAX_VARCHAR_LENGTH)

    init {
        index(isUnique = true, indexCode, src)
    }
}

//class IndustryClassify(id: EntityID<Int>) : IntEntity(id) {
//    companion object : IntEntityClass<IndustryClassify>(IndustryClassifyTable)
//
//    var indexCode by IndustryClassifyTable.indexCode
//    var industryName by IndustryClassifyTable.industryName
//    var parentCode by IndustryClassifyTable.parentCode
//    var level by IndustryClassifyTable.level
//    var industryCode by IndustryClassifyTable.industryCode
//    var isPub by IndustryClassifyTable.isPub
//    var src by IndustryClassifyTable.src
//}
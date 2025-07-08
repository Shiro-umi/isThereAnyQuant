@file:Suppress("SqlNoDataSourceInspection")

package org.shiroumi.database.table

import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.varchar
import org.shiroumi.database.commonDb
import org.shiroumi.model.database.StockBasicInfo

private const val TableName: String = "StockBasicInfo"

// symbol table def
object StockBasicInfoTable : Table<StockBasicInfo>(TableName) {
    val tsCode = varchar("tsCode").bindTo { s -> s.tsCode }
    val code = varchar("code").bindTo { s -> s.code }
    val name = varchar("name").bindTo { s -> s.name }
    val area     = varchar("area").bindTo { s -> s.area }
    val industry = varchar("industry").bindTo { s -> s.industry }
    val cnSpell  = varchar("cnSpell").bindTo { s -> s.cnSpell }
    val market   = varchar("market").bindTo { s -> s.market }
}


// candle seq of target table
val stockBasicSeq: EntitySequence<StockBasicInfo, out StockBasicInfoTable>
    get() {
        createSymbolTableIfNotExists()
        return commonDb.sequenceOf(StockBasicInfoTable)
    }

// new candle table
private fun createSymbolTableIfNotExists() {
    val sql = """
        CREATE TABLE IF NOT EXISTS $TableName (
            tsCode VARCHAR(255) NOT NULL,
            code VARCHAR(255) NOT NULL,
            name VARCHAR(255) NOT NULL,
            area VARCHAR(255) NOT NULL,
            industry VARCHAR(255) NOT NULL,
            cnSpell VARCHAR(255) NOT NULL,
            market VARCHAR(255) NOT NULL
        );
    """.trimIndent()
    commonDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}

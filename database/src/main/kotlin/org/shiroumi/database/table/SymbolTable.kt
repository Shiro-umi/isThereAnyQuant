package org.shiroumi.database.table

import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.varchar
import org.shiroumi.database.commonDb
import org.shiroumi.model.database.Symbol

private const val TableName: String = "symbol"

sealed class SymbolType(val value: String) {
    data object Stock : SymbolType("stock")
    data object Index : SymbolType("index")
    data object Concept : SymbolType("concept")
    data object Board : SymbolType("board")
}

// symbol table def
object SymbolTable : Table<Symbol>(TableName) {
    val code = varchar("code").bindTo { s -> s.code }
    val name = varchar("name").bindTo { s -> s.name }
    val type = varchar("type").bindTo { s -> s.type }
}


// candle seq of target table
val symbolSeq: EntitySequence<Symbol, out SymbolTable>
    get() {
        createSymbolTableIfNotExists()
        return commonDb.sequenceOf(SymbolTable)
    }

// new candle table
private fun createSymbolTableIfNotExists() {
    val sql = """
        CREATE TABLE IF NOT EXISTS $TableName (
            code VARCHAR(255) NOT NULL,
            name VARCHAR(255) NOT NULL,
            type VARCHAR(255) NOT NULL
        );
    """.trimIndent()
    commonDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}

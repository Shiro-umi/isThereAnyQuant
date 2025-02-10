package org.shiroumi.database.table

import org.ktorm.dsl.QuerySource
import org.ktorm.dsl.from
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.varchar
import org.ktorm.support.mysql.BulkInsertStatementBuilder
import org.ktorm.support.mysql.bulkInsert
import org.shiroumi.database.commonDb
import org.shiroumi.model.database.TradingDate

private const val TableName: String = "trading_date"

// symbol table def
object TradingDateTable : Table<TradingDate>(TableName) {
    val date = varchar("date").bindTo { t -> t.date }
}

// candle seq of target table
val tradingDateSeq: EntitySequence<TradingDate, out TradingDateTable>
    get() {
        createTradingDateTableIfNotExists()
        return commonDb.sequenceOf(TradingDateTable)
    }


fun <T> TradingDateTable.query(block: (source: QuerySource) -> List<T>): List<T> {
    createTradingDateTableIfNotExists()
    return block(commonDb.from(this))
}

fun TradingDateTable.bulkInsert(block: BulkInsertStatementBuilder<TradingDateTable>.() -> Unit) {
    commonDb.bulkInsert(this, block)
}

fun dropTradingDateTable() = commonDb.useConnection { conn ->
    conn.prepareStatement("drop table if exists trading_date").execute()
}

// new candle table
private fun createTradingDateTableIfNotExists() {
    val sql = """
        CREATE TABLE IF NOT EXISTS $TableName (
            date VARCHAR(255) NOT NULL
        );
    """.trimIndent()
    commonDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}

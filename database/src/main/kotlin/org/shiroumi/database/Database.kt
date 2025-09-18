package org.shiroumi.database

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.database.table.Candle

const val MAX_VARCHAR_LENGTH = 128

val stockDb =
    Database.connect(
        "jdbc:mysql://192.168.31.125:3306/stock?allowMultiQueries=true&rewriteBatchedInserts=true",
        driver = "org.h2.Driver",
        user = "remote",
        password = BuildConfigs.DATABASE_PASSWORD
    )

fun <T> Database.transaction(
    vararg tables: Table,
    log: Boolean = true,
    block: JdbcTransaction.() -> T
) = transaction(db = this) {
    if (log) addLogger(StdOutSqlLogger)
    SchemaUtils.create(*tables)
    return@transaction block()
}

operator fun <T> List<T>.component6() = get(5)

operator fun <T> List<T>.component7() = get(6)

operator fun <T> List<T>.component8() = get(7)

operator fun <T> List<T>.component9() = get(8)

operator fun <T> List<T>.component10() = get(9)

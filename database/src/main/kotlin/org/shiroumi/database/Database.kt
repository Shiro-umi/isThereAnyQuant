package org.shiroumi.database

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.shiroumi.configs.BuildConfigs
import java.sql.DriverManager

const val MAX_VARCHAR_LENGTH = 128

val commonDb: Database by lazy {
    createDb(dbName = "common_db")
    Database.connect(
        "jdbc:mysql://127.0.0.1:3306/common_db?rewriteBatchedStatements=true&allowMultiQueries=true",
        driver = "com.mysql.cj.jdbc.Driver",
        user = "remote",
        password = BuildConfigs.DATABASE_PASSWORD,
        databaseConfig = DatabaseConfig {
            useNestedTransactions = false
        }
    )
}

val stockDb: Database by lazy {
    createDb(dbName = "stock_db")
    Database.connect(
        "jdbc:mysql://127.0.0.1:3306/stock_db?rewriteBatchedStatements=true&allowMultiQueries=true",
        driver = "com.mysql.cj.jdbc.Driver",
        user = "remote",
        password = BuildConfigs.DATABASE_PASSWORD,
        databaseConfig = DatabaseConfig {
            useNestedTransactions = false
        }
    )
}

val swIndexDb: Database by lazy {
    createDb(dbName = "sw_index_db")
    Database.connect(
        "jdbc:mysql://127.0.0.1:3306/sw_index_db?rewriteBatchedStatements=true&allowMultiQueries=true",
        driver = "com.mysql.cj.jdbc.Driver",
        user = "remote",
        password = BuildConfigs.DATABASE_PASSWORD,
        databaseConfig = DatabaseConfig {
            useNestedTransactions = false
        }
    )
}

private fun createDb(dbName: String) = DriverManager.getConnection(
    "jdbc:mysql://127.0.0.1:3306/?user=remote&password=${BuildConfigs.DATABASE_PASSWORD}"
).use { connection ->
    connection.createStatement().use { statement ->
        statement.executeUpdate("CREATE DATABASE IF NOT EXISTS $dbName")
    }
}

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
operator fun <T> List<T>.component11() = get(10)
operator fun <T> List<T>.component12() = get(11)
operator fun <T> List<T>.component13() = get(12)
operator fun <T> List<T>.component14() = get(13)
operator fun <T> List<T>.component15() = get(14)

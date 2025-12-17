package org.shiroumi.database.old

import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.exposedLogger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.shiroumi.configs.BuildConfigs
import java.sql.DriverManager

const val MAX_VARCHAR_LENGTH = 128

val stockDb =
    Database.connect(
        "jdbc:mysql://127.0.0.1:3306/stock?allowMultiQueries=true&rewriteBatchedInserts=true",
        driver = "com.mysql.cj.jdbc.Driver",
        user = "root",
        password = BuildConfigs.DATABASE_PASSWORD
    )

val shenwanDb by lazy {
    initShenwanDb()
    Database.connect(
        "jdbc:mysql://127.0.0.1:3306/shenwan?allowMultiQueries=true&rewriteBatchedInserts=true",
        driver = "com.mysql.cj.jdbc.Driver",
        user = "root",
        password = BuildConfigs.DATABASE_PASSWORD
    )
}

fun initShenwanDb() {
    try {
        DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:3306/?user=root&password=${BuildConfigs.DATABASE_PASSWORD}"
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE DATABASE IF NOT EXISTS shenwan")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun <T> Database.transaction(
    vararg tables: Table,
    log: Boolean = true,
    block: JdbcTransaction.() -> T
) = transaction(db = this) {
    if (log) addLogger(StdOutSqlLogger)
    if (tables.isNotEmpty()) SchemaUtils.create(*tables)
    return@transaction block()
}
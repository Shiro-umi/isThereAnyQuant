package org.shiroumi.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.ktorm.database.Database

// database instance
val commonDb = Database.connect(Schema.Common.connectionPool)
val stockDb = Database.connect(Schema.Stock.connectionPool)


// schema def
sealed class Schema(val value: String) {
    data object Common : Schema("common")
    data object Stock : Schema("stock")
}

// connection pool
val Schema.connectionPool: HikariDataSource
    get() = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://192.168.31.125:3306/$value?allowMultiQueries=true"
            username = "remote"
            password = "Zhouzheng941206."
            driverClassName = "com.mysql.cj.jdbc.Driver"

            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000 // 30秒
            idleTimeout = 600000 // 10分钟
            maxLifetime = 1800000 // 30分钟
            connectionTestQuery = "SELECT 1"

            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }
    )
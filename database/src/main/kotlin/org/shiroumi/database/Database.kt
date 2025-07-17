package org.shiroumi.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cpuCores
import org.ktorm.database.Database
import org.ktorm.support.mysql.MySqlDialect
import org.shiroumi.configs.BuildConfigs

// database instance
val commonDb = Database.connect(Schema.Common.connectionPool, dialect = MySqlDialect())
val stockDb = Database.connect(Schema.Stock.connectionPool, dialect = MySqlDialect())


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
            username = BuildConfigs.DATABASE_USERNAME
            password = BuildConfigs.DATABASE_PASSWORD
            driverClassName = "com.mysql.cj.jdbc.Driver"
            maximumPoolSize = cpuCores * 2
        }
    )
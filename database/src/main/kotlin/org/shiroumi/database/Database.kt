package org.shiroumi.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.shiroumi.config.ConfigManager
import org.shiroumi.config.ConfigHolder
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val MAX_VARCHAR_LENGTH = 128

/**
 * 测试模式判断
 * 唯一的配置源：ConfigHolder
 */
val isTestMode: Boolean
    get() {
        val config = ConfigHolder.config
        // 兜底加载
        if (config.server.port == 9870 && !isConfigInit) {
            ConfigManager.load()
            isConfigInit = true
        }
        return config.server.testMode
    }

private var isConfigInit = false

/**
 * 获取数据库名称（测试模式下自动添加 _test 后缀）
 * 
 * 只有业务数据库（任务）应用测试后缀。
 * 基础数据库（股票、日历、指数）在所有模式下共享，避免数据冗余。
 */
fun getDbName(baseName: String): String {
    val needsIsolation = baseName == "ai_analysis_db"
    return if (isTestMode && needsIsolation) {
        if (baseName.endsWith("_test")) baseName else "${baseName}_test"
    } else {
        baseName
    }
}

/**
 * 已执行过 `createMissingTablesAndColumns` 的表名缓存。
 * 避免高频事务中重复做 metadata 查询，降低 backfill 场景下的连接压力。
 */
private val ensuredTables = ConcurrentHashMap<String, Boolean>()

/**
 * 获取驱动连接 URL
 */
private fun getJdbcUrl(dbName: String, socketTimeoutMs: Int): String {
    val db = ConfigHolder.config.database
    return "jdbc:mysql://${db.host}:${db.port}/${dbName}?" +
            "rewriteBatchedStatements=true&" +
            "allowMultiQueries=true&" +
            "autoReconnect=true&" +
            "maxReconnects=10&" +
            "initialTimeout=2&" +
            "connectTimeout=$DB_CONNECT_TIMEOUT_MS&" +
            "socketTimeout=$socketTimeoutMs&" +
            "serverTimezone=Asia/Shanghai&" +
            "useSSL=false&" +
            "allowPublicKeyRetrieval=true&" +
            "useUnicode=true&" +
            "characterEncoding=UTF-8&" +
            "cachePrepStmts=true&" +
            "prepStmtCacheSize=250&" +
            "prepStmtCacheSqlLimit=2048"
}

val commonDb: Database by lazy { connect("common_db") }
internal val stockDb: Database by lazy { connect("stock_db") }

private fun connect(baseName: String): Database {
    val dbName = getDbName(baseName)
    val dbConfig = ConfigHolder.config.database
    val poolConfig = dbConfig.pool
    
    // 确保数据库存在
    createDb(dbName = dbName)

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = getJdbcUrl(dbName, poolConfig.socketTimeoutMs)
        driverClassName = "com.mysql.cj.jdbc.Driver"
        username = dbConfig.user
        password = dbConfig.password
        maximumPoolSize = poolConfig.maxConnections.coerceAtLeast(1)
        minimumIdle = 0
        connectionTimeout = poolConfig.connectionTimeoutMs.coerceAtLeast(HIKARI_MIN_TIMEOUT_MS)
        validationTimeout = poolConfig.connectionTimeoutMs
            .coerceIn(HIKARI_MIN_TIMEOUT_MS, 5_000L)
        initializationFailTimeout = -1
        poolName = "quant-$dbName"
    }

    return Database.connect(
        datasource = HikariDataSource(hikariConfig),
        databaseConfig = DatabaseConfig {
            useNestedTransactions = false
        }
    )
}

private fun createDb(dbName: String) {
    val db = ConfigHolder.config.database
    DriverManager.getConnection(
        "jdbc:mysql://${db.host}:${db.port}/?connectTimeout=$DB_CONNECT_TIMEOUT_MS&socketTimeout=$DB_CREATE_SOCKET_TIMEOUT_MS&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
        db.user,
        db.password
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS $dbName")
        }
    }
}

internal fun <T> Database.transaction(
    vararg tables: Table,
    log: Boolean = false,
    block: JdbcTransaction.() -> T
) = transaction(db = this) {
    if (log) addLogger(StdOutSqlLogger)
    val missing = tables.filterNot { ensuredTables.putIfAbsent(it.tableName, true) == true }
    if (missing.isNotEmpty()) {
        SchemaUtils.createMissingTablesAndColumns(*missing.toTypedArray())
    }
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

private val DB_CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
private val DB_CREATE_SOCKET_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)
private val HIKARI_MIN_TIMEOUT_MS = 250L

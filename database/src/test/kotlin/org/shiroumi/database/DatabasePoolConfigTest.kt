package org.shiroumi.database

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.config.ConfigHolder
import org.shiroumi.config.DatabaseConfig
import org.shiroumi.config.DatabasePoolConfig
import org.shiroumi.config.QuantConfig

/**
 * OPT-2-pool 连接池扩容单测。
 *
 * 验证三层不变量：
 * 1. [DatabasePoolConfig] 默认值是生产中等并发推荐档（单库 25 / 常驻 8），32G 内存富余。
 * 2. [buildHikariConfig] 纯装配函数把配置正确翻译成 HikariCP 参数，且对越界配置做安全 coerce
 *    （maxConnections<1、minimumIdle>maxConnections、connectionTimeout 过小）。
 * 3. 分库独立池：common_db / stock_db 各自得到独立 poolName 与可叠加的总连接预算；
 *    测试模式 ai_analysis_db 走 _test 隔离，stock_db 不变。
 *
 * 全部用例只装配 HikariConfig，不发起任何真实数据库连接（createDb 不被触发），可在 CI 离线运行。
 */
class DatabasePoolConfigTest {

    private lateinit var savedConfig: QuantConfig

    @BeforeEach
    fun setUp() {
        // 备份全局配置，测试结束恢复，避免污染其它测试。
        savedConfig = ConfigHolder.config
    }

    @AfterEach
    fun tearDown() {
        ConfigHolder.update(savedConfig)
    }

    // ---------------------------------------------------------------------
    // 1. 配置默认值正确性
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("默认连接池档位 = 中等并发推荐（25 / 8 / 5s / 60s）")
    fun defaultPoolValuesAreMediumConcurrencyTier() {
        val pool = QuantConfig().database.pool
        assertEquals(25, pool.maxConnections, "默认单库峰值连接应为 25（中等并发档）")
        assertEquals(8, pool.minimumIdle, "默认常驻空闲连接应为 8")
        assertEquals(5000L, pool.connectionTimeoutMs, "默认获取连接超时应为 5000ms")
        assertEquals(60000, pool.socketTimeoutMs, "默认 socket 读超时应为 60000ms")
    }

    @Test
    @DisplayName("分库默认总连接预算 = 2 库 × 25 = 50（从 20 提升到 50，+150% 容量）")
    fun defaultTotalConnectionBudgetAcrossTwoShards() {
        val perShard = QuantConfig().database.pool.maxConnections
        val totalBudget = perShard * 2 // common_db + stock_db
        assertEquals(50, totalBudget, "2 库各 25 连接，总预算应为 50")
        assertTrue(totalBudget > 20, "必须高于旧基准 20（2库×10），证明扩容生效")
    }

    // ---------------------------------------------------------------------
    // 2. 配置文件覆盖加载（与生产同款 kaml 解析器）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("YAML 覆盖 pool 段：maxConnections=40, minimumIdle=12 正确反序列化")
    fun yamlOverrideDeserializesPool() {
        val yaml = """
            database:
              host: "10.0.0.1"
              port: 3306
              user: "remote"
              password: "secret"
              pool:
                maxConnections: 40
                minimumIdle: 12
                connectionTimeoutMs: 3000
                socketTimeoutMs: 45000
        """.trimIndent()

        val config = Yaml.default.decodeFromString(QuantConfig.serializer(), yaml)
        val pool = config.database.pool
        assertEquals(40, pool.maxConnections)
        assertEquals(12, pool.minimumIdle)
        assertEquals(3000L, pool.connectionTimeoutMs)
        assertEquals(45000, pool.socketTimeoutMs)
        assertEquals("10.0.0.1", config.database.host)
    }

    @Test
    @DisplayName("YAML 缺省 pool 段时继承代码默认值（生产 config.yaml 无 pool 即自动得到扩容档）")
    fun yamlWithoutPoolInheritsDefaults() {
        val yaml = """
            database:
              host: "127.0.0.1"
              port: 3306
              user: "remote"
              password: "secret"
        """.trimIndent()

        val config = Yaml.default.decodeFromString(QuantConfig.serializer(), yaml)
        val pool = config.database.pool
        // 这正是生产 config.yaml 未显式写 pool 时的行为：直接拿到 25/8 扩容档。
        assertEquals(25, pool.maxConnections)
        assertEquals(8, pool.minimumIdle)
    }

    // ---------------------------------------------------------------------
    // 3. Hikari 池装配验证（纯函数，不连真实库）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("buildHikariConfig 把 25/8 配置正确翻译成 HikariCP 参数")
    fun buildHikariConfigAppliesMediumTier() {
        val dbConfig = DatabaseConfig(
            host = "127.0.0.1",
            port = 3306,
            user = "remote",
            password = "pw",
            pool = DatabasePoolConfig(maxConnections = 25, minimumIdle = 8)
        )

        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(25, hikari.maximumPoolSize)
        assertEquals(8, hikari.minimumIdle)
        assertEquals("quant-common_db", hikari.poolName)
        assertEquals("remote", hikari.username)
        assertEquals("com.mysql.cj.jdbc.Driver", hikari.driverClassName)
        assertTrue(hikari.jdbcUrl.contains("/common_db?"), "jdbcUrl 应指向 common_db")
        assertTrue(hikari.jdbcUrl.contains("socketTimeout=60000"), "jdbcUrl 应带 socketTimeout=60000")
        // initializationFailTimeout=-1 表示装配阶段不强制建连，离线可装配。
        assertEquals(-1L, hikari.initializationFailTimeout)
    }

    @Test
    @DisplayName("自定义高并发档 40/12 装配后 HikariCP 参数完全对齐")
    fun buildHikariConfigAppliesHighConcurrencyTier() {
        val dbConfig = DatabaseConfig(
            pool = DatabasePoolConfig(
                maxConnections = 40,
                minimumIdle = 12,
                connectionTimeoutMs = 4000,
                socketTimeoutMs = 90000
            )
        )
        val hikari = buildHikariConfig("stock_db", dbConfig)
        assertEquals(40, hikari.maximumPoolSize)
        assertEquals(12, hikari.minimumIdle)
        assertEquals(4000L, hikari.connectionTimeout)
        assertTrue(hikari.jdbcUrl.contains("socketTimeout=90000"))
        assertEquals("quant-stock_db", hikari.poolName)
    }

    // ---------------------------------------------------------------------
    // 4. 边界情况：越界配置安全 coerce
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("maxConnections<1 时 coerceAtLeast(1) 生效，池不会塌成 0")
    fun maxConnectionsBelowOneCoercedToOne() {
        val dbConfig = DatabaseConfig(pool = DatabasePoolConfig(maxConnections = 0, minimumIdle = 0))
        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(1, hikari.maximumPoolSize, "maxConnections=0 必须被抬到 1")
    }

    @Test
    @DisplayName("minimumIdle>maxConnections 时截断到 maxConnections（HikariCP 不再静默拉平）")
    fun minimumIdleAboveMaxIsTruncated() {
        val dbConfig = DatabaseConfig(pool = DatabasePoolConfig(maxConnections = 10, minimumIdle = 30))
        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(10, hikari.maximumPoolSize)
        assertEquals(10, hikari.minimumIdle, "minimumIdle 越界应截断到 maxConnections=10")
    }

    @Test
    @DisplayName("minimumIdle 负数被 coerce 到 0，不传非法值给 HikariCP")
    fun minimumIdleNegativeCoercedToZero() {
        val dbConfig = DatabaseConfig(pool = DatabasePoolConfig(maxConnections = 25, minimumIdle = -5))
        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(0, hikari.minimumIdle, "负 minimumIdle 应被 coerce 到 0")
    }

    @Test
    @DisplayName("connectionTimeoutMs 低于 Hikari 下限 250ms 时被抬到 250ms")
    fun connectionTimeoutBelowFloorCoerced() {
        val dbConfig = DatabaseConfig(
            pool = DatabasePoolConfig(maxConnections = 25, minimumIdle = 8, connectionTimeoutMs = 50)
        )
        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(250L, hikari.connectionTimeout, "connectionTimeout 应被抬到下限 250ms")
        // validationTimeout 同步收敛在 [250, 5000]。
        assertTrue(hikari.validationTimeout in 250L..5000L)
    }

    @Test
    @DisplayName("connectionTimeoutMs 超大时 validationTimeout 收敛在 5000ms 上限")
    fun validationTimeoutCappedAtFiveSeconds() {
        val dbConfig = DatabaseConfig(
            pool = DatabasePoolConfig(maxConnections = 25, minimumIdle = 8, connectionTimeoutMs = 60000)
        )
        val hikari = buildHikariConfig("common_db", dbConfig)
        assertEquals(60000L, hikari.connectionTimeout, "connectionTimeout 保留原值")
        assertEquals(5000L, hikari.validationTimeout, "validationTimeout 上限 5000ms")
    }

    // ---------------------------------------------------------------------
    // 5. 多数据库独立池
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("common_db 与 stock_db 各自独立池：poolName 不同，总连接可叠加")
    fun independentPoolsPerShard() {
        val dbConfig = DatabaseConfig(pool = DatabasePoolConfig(maxConnections = 25, minimumIdle = 8))

        val common = buildHikariConfig("common_db", dbConfig)
        val stock = buildHikariConfig("stock_db", dbConfig)

        assertEquals("quant-common_db", common.poolName)
        assertEquals("quant-stock_db", stock.poolName)
        assertTrue(common.poolName != stock.poolName, "两个分库必须是独立 poolName")

        val totalConnections = common.maximumPoolSize + stock.maximumPoolSize
        assertEquals(50, totalConnections, "两库总连接 = 25 + 25 = 50")
    }

    // ---------------------------------------------------------------------
    // 6. 测试模式隔离
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("测试模式下 ai_analysis_db 走 _test 隔离，stock_db / common_db 不变")
    fun testModeIsolatesAiAnalysisDbOnly() {
        // 显式设置非 9870 端口，避开 isTestMode 的 ConfigManager.load() 兜底重载逻辑，
        // 否则兜底加载会用默认配置覆盖 testMode=true。
        ConfigHolder.update(
            savedConfig.copy(
                server = savedConfig.server.copy(testMode = true, port = 19999)
            )
        )
        assertEquals("ai_analysis_db_test", getDbName("ai_analysis_db"), "ai_analysis_db 测试模式加 _test")
        assertEquals("stock_db", getDbName("stock_db"), "stock_db 不做测试隔离")
        assertEquals("common_db", getDbName("common_db"), "common_db 不做测试隔离")

        // 已带 _test 后缀时不重复追加。
        assertEquals("ai_analysis_db_test", getDbName("ai_analysis_db_test"))
    }

    @Test
    @DisplayName("非测试模式下 ai_analysis_db 保持原名")
    fun nonTestModeKeepsBaseName() {
        ConfigHolder.update(
            savedConfig.copy(
                server = savedConfig.server.copy(testMode = false, port = 19999)
            )
        )
        assertEquals("ai_analysis_db", getDbName("ai_analysis_db"))
        assertEquals("stock_db", getDbName("stock_db"))
    }
}

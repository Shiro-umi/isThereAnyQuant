@file:Suppress("unused")

package org.shiroumi.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ktor.module.compression
import ktor.module.configureStaticContent
import ktor.module.configureForwardedHeaders
import ktor.module.configureSecurity
import ktor.module.configureStatusPages
import ktor.module.contentNegotiation
import ktor.module.ktorRouting
import ktor.module.refreshTokenRepository
import ktor.module.userRepository
import ktor.module.websockets
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.agent.AgentSchemaBootstrap
import org.shiroumi.database.strategy.daily.StrategyStateSchemaBootstrap
import org.shiroumi.server.dataprovider.bootstrap.DataProviderBootstrap
import org.shiroumi.server.service.DataUpdateService
import utils.logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger by logger("Processor")

/**
 * 注册 JDBC 驱动
 */
fun registerJdbcDrivers() {
    try {
        Class.forName("com.mysql.cj.jdbc.Driver")
    } catch (_: Exception) {}
    try {
        Class.forName("org.h2.Driver")
    } catch (_: Exception) {}
}

/**
 * 启动入口
 */
@OptIn(ExperimentalAtomicApi::class)
fun main(args: Array<String>) {
    registerJdbcDrivers()

    // 1. 显式加载配置 (全局唯一入口)
    val config = ConfigManager.load()
    
    // 2. 确定运行模式
    val isTestMode = config.server.testMode
    
    // 3. 确定监听地址。host/port 必须来自统一配置，不能在运行入口按模式硬编码兜底。
    val port = config.server.port
    val host = config.server.host

    logger.info("正在启动服务器: host=$host, 端口=$port, 测试模式=$isTestMode")
    logger.info("数据库 Host: ${config.database.host}, 用户: ${config.database.user}")

    runBlocking {
        embeddedServer(
            io.ktor.server.cio.CIO,
            port = port,
            host = host,
            module = Application::module
        ).start(wait = true)
    }
}

fun Application.module() {

    // 启动清扫：清理上次崩溃残留的孤儿进程
    org.shiroumi.server.agent.AgentProcessRegistry.cleanupOrphans()

    // 注册关停钩子
    monitor.subscribe(ApplicationStopping) {
        org.shiroumi.server.websocket.service.AgentWebSocketService.shutdown()
        org.shiroumi.server.agent.AgentProcessRegistry.shutdownAll(graceMillis = 5000)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        org.shiroumi.server.agent.AgentProcessRegistry.shutdownAll(graceMillis = 2000)
    })

    // Cloudflare Tunnel 的 Forwarded Headers 必须在 Security 之前安装
    // 确保 CORS / JWT 等模块能读取到正确的原始请求信息
    configureForwardedHeaders()

    // configureSecurity 必须在 ensureSchema 之前调用，因为 Repository 实例在 configureSecurity 中创建
    configureSecurity()

        // 确保 common_db 中的认证表存在（幂等）
        launch {
            StrategyStateSchemaBootstrap.ensureSchema()

            // 启动新 DataProvider 架构底座。
            // 当前阶段先只启动 runtime 和装配容器，不切 HTTP / WebSocket 消费链路。
            DataProviderBootstrap.initialize()

        userRepository.ensureSchema()
        refreshTokenRepository.ensureSchema()
        org.shiroumi.database.user.createUserAgentConfigRepository().ensureSchema()
        org.shiroumi.database.common.repository.SystemStatusRepository.ensureSchema()
        AgentSchemaBootstrap.ensureSchema()
        
        // 在表结构准备好后启动调度器
        DataUpdateService.startScheduler()
    }

    contentNegotiation()
    configureStaticContent()
    compression()
    configureStatusPages()
    websockets()
    ktorRouting()
}

val today: String
    get() = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

val rootDir: String = System.getProperty("quant.project.root")
    ?: System.getProperty("user.dir")

package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.server.service.DataUpdateService

/**
 * 手动运行数据更新和策略预处理的独立入口。
 * 可以通过 Gradle task: ./gradlew :ktor-server:runUpdateDaily
 */
fun main() {
    // 注册 JDBC 驱动
    try {
        Class.forName("com.mysql.cj.jdbc.Driver")
    } catch (_: Exception) {}
    try {
        Class.forName("org.h2.Driver")
    } catch (_: Exception) {}

    // 加载配置（必须在使用 ConfigHolder 之前调用）
    ConfigManager.load()

    runBlocking {
        // 直接复用 DataUpdateService 的手动触发逻辑
        DataUpdateService.triggerUpdate()
    }
}

package org.shiroumi.config

/**
 * 跨平台应用配置持有者
 * 
 * 其初始值由编译时生成的 AppEnvironment 提供。
 * 后端仍可通过 ConfigManager.load() 加载 yaml 来覆盖其中的部分逻辑（如数据库连接），
 * 但前端访问的基础 URL 和端口在编译时即锁定。
 */
object AppConfig {

    val mode: String = AppEnvironment.MODE
    
    val baseUrl: String = AppEnvironment.BASE_URL
    
    val port: Int = AppEnvironment.PORT
    
    val testMode: Boolean = AppEnvironment.IS_TEST_MODE
    
    /**
     * 获取最终的 API 基础 URL
     */
    val apiBaseUrl: String = AppEnvironment.apiBaseUrl

    /**
     * 获取最终的 WebSocket 基础 URL
     */
    val wsBaseUrl: String = AppEnvironment.wsBaseUrl

    /**
     * 客户端安装包夸克网盘固定分享页地址（编译期由 config.yaml 的 client.downloadUrl 注入）
     */
    val clientDownloadUrl: String = AppEnvironment.CLIENT_DOWNLOAD_URL
}

package ktor.module

import io.ktor.server.application.*
import io.ktor.server.routing.*
import ktor.auth.AuthService
import ktor.auth.authRoutes
import ktor.module.routing.*
import org.shiroumi.server.route.agentAnalysisRoutes
import org.shiroumi.server.route.agentConfigRoutes
import org.shiroumi.server.route.backtestRoutes
import org.shiroumi.server.route.strategyRoutes
import org.shiroumi.server.share.publicShareRoutes

fun Application.ktorRouting() {
    // 初始化认证服务（注入来自 Security.kt 存储的 Repository 实例）
    val authService = AuthService(
        userRepository = userRepository,
        tokenRepository = refreshTokenRepository,
        jwtService = jwtService
    )

    routing {
        // 认证路由
        authRoutes(authService)

        // API v1 路由组
        route("/api/v1") {
            stockRoutes()
            strategyRoutes()
            agentAnalysisRoutes()
            agentConfigRoutes()
        }

        // 全局 WebSocket 多路复用流
        appStreamWebSocket()

        // 预加载API
        preloadData(route = "/api/preload")

        // 数据更新API
        dataUpdateRoutes()

        // 回测 API：同步返回本次结果，现阶段不落库
        backtestRoutes()

        // APK 下载路由
        downloadRoutes()

        // Local CLI tools logic API
        internalCliRoutes()
        researchDataRoutes()

        // 匿名分享：/s/{token} HTML 页 + /api/v1/public/share/{token}/candles
        // 必须挂在 SPA fallback 之前，否则会被 SPA 接住
        shareStaticResources()
        publicShareRoutes()

        // 独立简历页：直接读取运行机本地 HTML，不进入 Compose SPA 或 git 跟踪资源。
        resumeRoute()

        // SPA 应该放在最后，以免拦截 API 请求
        composeApp(route = "/")
    }
}

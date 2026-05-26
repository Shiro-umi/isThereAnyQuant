package ktor.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import model.auth.*

/**
 * 认证路由配置
 */
fun Route.authRoutes(authService: AuthService) {

    // ==================== 公开路由 ====================

    /**
     * 用户注册
     * POST /api/auth/register
     */
    post("/api/auth/register") {
        val request = call.receive<RegisterRequest>()

        when (val result = authService.register(request)) {
            is AuthResult.Success -> call.respond(HttpStatusCode.Created, result.data)
            is AuthResult.Error -> call.respondAuthError(
                result.code, result.message, getHttpStatus(result.code)
            )
        }
    }

    /**
     * 用户登录
     * POST /api/auth/login
     */
    post("/api/auth/login") {
        val request = call.receive<LoginRequest>()
        val deviceInfo = call.request.headers["User-Agent"]
        val ipAddress = call.request.header("X-Forwarded-For")
            ?: call.request.header("X-Real-IP")
            ?: "unknown"

        when (val result = authService.login(request, deviceInfo, ipAddress)) {
            is AuthResult.Success -> {
                call.setRefreshTokenCookie(result.data.refreshToken, request.rememberMe)
                call.respond(HttpStatusCode.OK, result.data)
            }
            is AuthResult.Error -> call.respondAuthError(
                result.code, result.message, getHttpStatus(result.code)
            )
        }
    }

    /**
     * 刷新 Access Token
     * POST /api/auth/refresh
     */
    post("/api/auth/refresh") {
        val refreshToken = call.request.cookies[AuthConstants.REFRESH_TOKEN_COOKIE]
            ?: run {
                val requestToken = runCatching {
                    call.receiveNullable<RefreshTokenRequest>()?.refreshToken
                }.getOrNull()
                if (requestToken.isNullOrBlank()) {
                    return@post call.respondAuthError(
                        AuthErrorCodes.UNAUTHORIZED,
                        "缺少 Refresh Token",
                        HttpStatusCode.Unauthorized
                    )
                }
                requestToken
            }

        when (val result = authService.refreshAccessToken(refreshToken)) {
            is AuthResult.Success -> {
                val (accessToken, expiresIn) = result.data
                // 兼容旧版已发出的 session refresh cookie：刷新成功时升级为持久 cookie，避免部署/PWA 恢复后掉登录态。
                call.setRefreshTokenCookie(refreshToken, rememberMe = true)
                call.respond(HttpStatusCode.OK, RefreshTokenResponse(accessToken, expiresIn))
            }
            is AuthResult.Error -> call.respondAuthError(
                result.code, result.message, HttpStatusCode.Unauthorized
            )
        }
    }

    // ==================== 需要认证的路由 ====================

    authenticate("auth-jwt") {

        /**
         * 获取当前用户信息
         * GET /api/auth/me
         */
        get("/api/auth/me") {
            val userId = call.getCurrentUserId()
                ?: return@get call.respondAuthError(
                    AuthErrorCodes.UNAUTHORIZED,
                    "无法获取用户信息",
                    HttpStatusCode.Unauthorized
                )

            when (val result = authService.getCurrentUser(userId)) {
                is AuthResult.Success -> call.respond(HttpStatusCode.OK, result.data)
                is AuthResult.Error -> call.respondAuthError(
                    result.code, result.message, getHttpStatus(result.code)
                )
            }
        }

        /**
         * 修改密码
         * POST /api/auth/password
         */
        post("/api/auth/password") {
            val userId = call.getCurrentUserId()
                ?: return@post call.respondAuthError(
                    AuthErrorCodes.UNAUTHORIZED,
                    "无法获取用户信息",
                    HttpStatusCode.Unauthorized
                )

            val request = call.receive<ChangePasswordRequest>()

            when (val result = authService.changePassword(userId, request.oldPassword, request.newPassword)) {
                is AuthResult.Success -> call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "密码修改成功，请重新登录")
                )
                is AuthResult.Error -> call.respondAuthError(
                    result.code, result.message, getHttpStatus(result.code)
                )
            }
        }

        /**
         * 用户登出
         * POST /api/auth/logout
         * 可选请求体：{ "logoutAllDevices": true } 表示登出所有设备
         */
        post("/api/auth/logout") {
            val userId = call.getCurrentUserId()
                ?: return@post call.respondAuthError(
                    AuthErrorCodes.UNAUTHORIZED,
                    "无法获取用户信息",
                    HttpStatusCode.Unauthorized
                )

            val logoutRequest = call.receiveOptional<LogoutRequest>()
            val refreshToken = call.request.cookies[AuthConstants.REFRESH_TOKEN_COOKIE]
                ?: logoutRequest?.refreshToken

            val result = if (logoutRequest?.logoutAllDevices == true) {
                authService.logoutAll(userId)
            } else {
                authService.logout(refreshToken, userId)
            }

            when (result) {
                is AuthResult.Success -> {
                    call.clearRefreshTokenCookie()
                    call.respond(HttpStatusCode.OK, mapOf("message" to "登出成功"))
                }
                is AuthResult.Error -> call.respondAuthError(
                    result.code, result.message, getHttpStatus(result.code)
                )
            }
        }
    }
}

// ==================== 扩展函数 ====================

/**
 * 设置 Refresh Token Cookie
 * 根据请求协议决定 SameSite 设置：
 * - HTTPS: SameSite=None; Secure（允许跨域携带 Cookie）
 * - HTTP: 不设置 SameSite（兼容本地开发）
 */
private fun ApplicationCall.setRefreshTokenCookie(token: String, rememberMe: Boolean) {
    val isHttps = request.origin.scheme == "https"

    // 手动构建 Set-Cookie 字符串
    val cookieValue = buildString {
        append("${AuthConstants.REFRESH_TOKEN_COOKIE}=$token")
        append("; Path=/")
        append("; HttpOnly")

        if (rememberMe) {
            // 记住我：设置长期有效期（30天）
            append("; Max-Age=${AuthConstants.REMEMBER_ME_EXPIRY_SECONDS}")
        }
        // 没记住我：不设置 Max-Age，即为 Session Cookie，浏览器关闭后失效

        if (isHttps) {
            // HTTPS: 使用 SameSite=None 并标记 Secure
            append("; SameSite=None")
            append("; Secure")
        }
        // HTTP: 不设置 SameSite，浏览器使用默认行为（允许同站 POST 携带 Cookie）
    }

    response.headers.append("Set-Cookie", cookieValue)
}

/**
 * 清除 Refresh Token Cookie
 */
private fun ApplicationCall.clearRefreshTokenCookie() {
    val isHttps = request.origin.scheme == "https"

    val cookieValue = buildString {
        append("${AuthConstants.REFRESH_TOKEN_COOKIE}=")
        append("; Max-Age=0")
        append("; Path=/")
        append("; HttpOnly")
        if (isHttps) {
            append("; SameSite=None")
            append("; Secure")
        }
    }

    response.headers.append("Set-Cookie", cookieValue)
}

/**
 * 获取当前用户 ID
 */
fun ApplicationCall.getCurrentUserId(): java.util.UUID? =
    principal<UserPrincipal>()?.userId

/**
 * 可选接收请求体
 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveOptional(): T? =
    try {
        receive<T>()
    } catch (e: Exception) {
        null
    }

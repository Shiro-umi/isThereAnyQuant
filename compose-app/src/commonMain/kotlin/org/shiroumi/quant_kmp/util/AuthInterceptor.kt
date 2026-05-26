package org.shiroumi.quant_kmp.util

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 认证拦截器
 * 自动附加 Access Token 并处理 Token 刷新
 */
class AuthInterceptor {

    private val mutex = Mutex()
    private var isRefreshing = false

    /**
     * 配置 HttpClient 以支持认证
     */
    fun configureClient(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            // 1. 添加 Access Token
            val token = TokenManager.getAccessToken()
            if (token != null) {
                request.bearerAuth(token)
            }

            // 2. 执行请求
            val response = execute(request)

            // 3. 处理 401 错误（Token 过期）
            if (response.response.status == HttpStatusCode.Unauthorized) {
                // 尝试刷新 Token
                val newToken = refreshToken(force = true)

                if (newToken != null) {
                    // 使用新 Token 重试请求
                    request.headers.remove(HttpHeaders.Authorization)
                    request.bearerAuth(newToken)
                    execute(request)
                } else {
                    // 刷新失败，清除登录状态
                    TokenManager.clearTokens()
                    response
                }
            } else {
                response
            }
        }
    }

    /**
     * 刷新 Token
     * @return 新的 Access Token，刷新失败返回 null
     */
    private suspend fun refreshToken(force: Boolean): String? {
        // 防止并发刷新
        mutex.withLock {
            if (isRefreshing) {
                // 等待刷新完成
                while (isRefreshing) {
                    kotlinx.coroutines.delay(100)
                }
                return TokenManager.getAccessToken()
            }

            isRefreshing = true

            try {
                return TokenRefreshHandler.refresh(force).getOrNull()
            } catch (e: Exception) {
                return null
            } finally {
                isRefreshing = false
            }
        }
    }
}

/**
 * 为 HttpRequestBuilder 添加 Bearer Auth
 */
private fun HttpRequestBuilder.bearerAuth(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

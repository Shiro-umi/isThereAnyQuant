package org.shiroumi.quant_kmp.feature.auth

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import model.auth.*
import org.shiroumi.config.AppEnvironment
import org.shiroumi.quant_kmp.di.HttpClientProvider

/**
 * 认证仓库
 * 负责与后端认证 API 通信
 */
class AuthRepository : IAuthRepository {

    private val client = HttpClientProvider.authClient
    private val baseUrl = AppEnvironment.apiBaseUrl

    override suspend fun login(
        username: String,
        password: String,
        rememberMe: Boolean,
    ): Result<LoginResponse> = runCatching {
        val response = client.post("$baseUrl/api/auth/login") {
            setBody(LoginRequest(username, password, rememberMe))
        }

        if (response.status == HttpStatusCode.OK) {
            response.body<LoginResponse>()
        } else {
            throw response.toAuthException()
        }
    }

    override suspend fun register(
        username: String,
        password: String,
        nickname: String?,
    ): Result<RegisterResponse> = runCatching {
        val response = client.post("$baseUrl/api/auth/register") {
            setBody(RegisterRequest(username, password, nickname))
        }

        if (response.status == HttpStatusCode.Created) {
            response.body<RegisterResponse>()
        } else {
            throw response.toAuthException()
        }
    }

    override suspend fun refreshToken(refreshToken: String?): Result<RefreshTokenResponse> = runCatching {
        val response = client.post("$baseUrl/api/auth/refresh") {
            if (!refreshToken.isNullOrBlank()) {
                setBody(RefreshTokenRequest(refreshToken))
            }
        }

        if (response.status == HttpStatusCode.OK) {
            response.body<RefreshTokenResponse>()
        } else {
            throw response.toAuthException()
        }
    }

    override suspend fun getCurrentUser(accessToken: String): Result<UserProfile> = runCatching {
        val response = client.get("$baseUrl/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }

        if (response.status == HttpStatusCode.OK) {
            response.body<UserProfile>()
        } else {
            throw response.toAuthException()
        }
    }

    override suspend fun changePassword(
        accessToken: String,
        oldPassword: String,
        newPassword: String,
    ): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/api/auth/password") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(ChangePasswordRequest(oldPassword, newPassword))
        }

        if (response.status != HttpStatusCode.OK) {
            throw response.toAuthException()
        }
    }

    override suspend fun logout(
        accessToken: String,
        refreshToken: String?,
        logoutAllDevices: Boolean,
    ): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/api/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(LogoutRequest(refreshToken, logoutAllDevices))
        }

        if (response.status != HttpStatusCode.OK) {
            throw response.toAuthException()
        }
    }
}

/**
 * 认证异常
 */
class AuthException(
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * 将响应转换为认证异常
 */
private suspend fun io.ktor.client.statement.HttpResponse.toAuthException(): AuthException {
    val error = runCatching { body<AuthErrorResponse>() }.getOrNull()
    return if (error != null) {
        AuthException(error.error, error.message)
    } else {
        AuthException(
            code = "HTTP_${status.value}",
            message = "登录请求失败：HTTP ${status.value} ${status.description}"
        )
    }
}

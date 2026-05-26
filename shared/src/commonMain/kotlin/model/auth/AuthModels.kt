package model.auth

import kotlinx.serialization.Serializable

/**
 * 登录请求
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val rememberMe: Boolean = false
)

/**
 * 登录响应
 */
@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,  // 秒
    val user: UserProfile
)

/**
 * 注册请求
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null
)

/**
 * 注册响应
 */
@Serializable
data class RegisterResponse(
    val userId: String,
    val message: String = "注册成功"
)

/**
 * 刷新 Token 请求
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

/**
 * 刷新 Token 响应
 */
@Serializable
data class RefreshTokenResponse(
    val accessToken: String,
    val expiresIn: Long
)

/**
 * 修改密码请求
 */
@Serializable
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
)

/**
 * 用户资料
 */
@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val nickname: String?,
    val avatar: String?,
    val roles: List<UserRole>,
    val createdAt: String
)

/**
 * 认证错误响应
 */
@Serializable
data class AuthErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String? = null
)

/**
 * 登出请求
 */
@Serializable
data class LogoutRequest(
    val refreshToken: String? = null,
    val logoutAllDevices: Boolean = false
)

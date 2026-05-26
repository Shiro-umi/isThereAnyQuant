package org.shiroumi.quant_kmp.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import model.auth.LoginResponse
import util.DateTimeUtils

/**
 * Token 管理器
 * 管理 Access Token 和用户信息
 *
 * Web 端 Refresh Token 由浏览器 HttpOnly Cookie 管理。
 * Android 端通过平台 AuthSessionStore 持久化，保证进程重启后可刷新会话。
 */
object TokenManager {

    private var _accessToken: String? = null
    private var _refreshToken: String? = null
    private var _expiresAt: Long = 0
    private var _user: MutableStateFlow<UserInfo?> = MutableStateFlow(null)
    private var _hasRestored = false
    private var _sessionVersion = 0L
    private val mutex = Mutex()

    /**
     * 当前用户信息流
     */
    val user: StateFlow<UserInfo?> = _user.asStateFlow()

    /**
     * 是否已登录
     */
    val isLoggedIn: Boolean
        get() = _accessToken != null && !isTokenExpired()

    /**
     * 保存登录响应数据
     * @param response 登录响应
     */
    suspend fun restoreFromStorage() = mutex.withLock {
        if (_hasRestored) return@withLock
        _hasRestored = true

        val session = PlatformAuthSessionStore.load() ?: return@withLock
        _accessToken = session.accessToken
        _refreshToken = if (PlatformAuthSessionStore.storesRefreshToken) session.refreshToken else null
        _expiresAt = session.expiresAt
        _user.value = session.user

        if (isTokenExpired()) {
            _accessToken = null
            _expiresAt = 0
        }
    }

    suspend fun saveTokens(response: LoginResponse) = mutex.withLock {
        _accessToken = response.accessToken
        _refreshToken = if (PlatformAuthSessionStore.storesRefreshToken) response.refreshToken else null
        _expiresAt = currentTimeMillis() + (response.expiresIn * 1000)
        _user.value = UserInfo(
            id = response.user.id,
            username = response.user.username,
            nickname = response.user.nickname,
            avatar = response.user.avatar,
            roles = response.user.roles
        )
        persistSession()
    }

    /**
     * 获取 Access Token
     * @return Token 字符串，如果已过期或未登录则返回 null
     */
    fun getAccessToken(): String? {
        return if (isTokenExpired()) {
            null
        } else {
            _accessToken
        }
    }

    suspend fun getRefreshToken(): String? {
        restoreFromStorage()
        return mutex.withLock { _refreshToken }
    }

    suspend fun currentSessionVersion(): Long = mutex.withLock {
        _sessionVersion
    }

    /**
     * 更新 Access Token（刷新后使用）
     * @param newToken 新的 Access Token
     * @param expiresIn 新的过期时间（秒）
     */
    suspend fun updateAccessToken(newToken: String, expiresIn: Long) = updateAccessToken(
        newToken = newToken,
        expiresIn = expiresIn,
        expectedSessionVersion = null,
    )

    suspend fun updateAccessToken(
        newToken: String,
        expiresIn: Long,
        expectedSessionVersion: Long?,
    ): Boolean = mutex.withLock {
        if (expectedSessionVersion != null && expectedSessionVersion != _sessionVersion) {
            return@withLock false
        }
        _accessToken = newToken
        _expiresAt = currentTimeMillis() + (expiresIn * 1000)
        persistSession()
        true
    }

    /**
     * 清除所有 Token 和用户信息（登出时使用）
     */
    suspend fun clearTokens() = mutex.withLock {
        _sessionVersion += 1
        _accessToken = null
        _refreshToken = null
        _expiresAt = 0
        _user.value = null
        PlatformAuthSessionStore.clear()
    }

    /**
     * 检查 Token 是否过期
     * @return true 如果已过期
     */
    fun isTokenExpired(): Boolean {
        if (_accessToken == null) return true
        // 提前 60 秒认为过期，以便有时间刷新
        return currentTimeMillis() >= (_expiresAt - 60000)
    }

    /**
     * 获取 Token 剩余有效时间（秒）
     * @return 剩余秒数，如果已过期返回 0
     */
    fun getRemainingSeconds(): Long {
        if (_accessToken == null) return 0
        val remaining = (_expiresAt - currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    /**
     * 更新用户信息
     * @param nickname 新昵称
     * @param avatar 新头像
     */
    suspend fun updateUserInfo(nickname: String? = null, avatar: String? = null) = mutex.withLock {
        _user.value?.let { currentUser ->
            _user.value = currentUser.copy(
                nickname = nickname ?: currentUser.nickname,
                avatar = avatar ?: currentUser.avatar
            )
            persistSession()
        }
    }

    /**
     * 更新完整用户信息
     * @param userInfo 用户信息
     */
    suspend fun updateUser(userInfo: UserInfo) = mutex.withLock {
        _user.value = userInfo
        persistSession()
    }

    private suspend fun persistSession() {
        PlatformAuthSessionStore.save(
            AuthSession(
                accessToken = _accessToken,
                refreshToken = _refreshToken,
                expiresAt = _expiresAt,
                user = _user.value,
            )
        )
    }
}

/**
 * 用户信息数据类
 */
@Serializable
data class UserInfo(
    val id: String,
    val username: String,
    val nickname: String?,
    val avatar: String?,
    val roles: List<model.auth.UserRole>
) {
    /**
     * 获取显示名称
     */
    fun getDisplayName(): String {
        return nickname ?: username
    }

    /**
     * 检查是否有指定角色
     */
    fun hasRole(role: model.auth.UserRole): Boolean {
        return roles.contains(role)
    }

    /**
     * 是否是管理员
     */
    fun isAdmin(): Boolean {
        return roles.contains(model.auth.UserRole.ADMIN)
    }
}

/**
 * 获取当前时间戳（毫秒）
 */
private fun currentTimeMillis(): Long {
    return DateTimeUtils.now()
}

package ktor.auth

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import model.auth.AuthConstants
import model.auth.AuthErrorCodes
import model.auth.LoginRequest
import model.auth.LoginResponse
import model.auth.RegisterRequest
import model.auth.RegisterResponse
import model.auth.UserProfile
import model.auth.UserRole
import org.shiroumi.database.user.model.UserModel
import org.shiroumi.database.user.repository.RefreshTokenRepository
import org.shiroumi.database.user.repository.UserRepository
import java.security.MessageDigest
import java.util.UUID

/**
 * 认证服务
 * 处理登录、注册、登出等认证业务逻辑
 *
 * @param userRepository 用户数据访问层（注入 commonDb 实例）
 * @param tokenRepository 刷新令牌数据访问层（注入 commonDb 实例）
 * @param jwtService JWT 服务
 */
class AuthService(
    private val userRepository: UserRepository,
    private val tokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService
) {

    // ==================== 公开业务方法 ====================

    /**
     * 用户登录（账号密码方式）
     *
     * @param request 登录请求（username、password、rememberMe）
     * @param deviceInfo 设备信息（User-Agent），可为 null
     * @param ipAddress 客户端 IP 地址，可为 null
     * @return 登录结果
     */
    suspend fun login(
        request: LoginRequest,
        deviceInfo: String? = null,
        ipAddress: String? = null
    ): AuthResult<LoginResponse> {
        // 1. 查找用户
        val user = userRepository.findByUsername(request.username)
            ?: return AuthResult.Error(AuthErrorCodes.USER_NOT_FOUND, "用户不存在")

        // 2. 检查账户是否被锁定
        if (user.isLocked()) {
            val remainingMinutes = getRemainingLockMinutes(user)
            return AuthResult.Error(
                AuthErrorCodes.ACCOUNT_LOCKED,
                "账户已被锁定，请 $remainingMinutes 分钟后重试"
            )
        }

        // 3. 检查账户是否激活
        if (!user.isActive) {
            return AuthResult.Error(AuthErrorCodes.ACCOUNT_DISABLED, "账户已被禁用")
        }

        // 4. 验证密码
        if (!PasswordService.verifyPassword(request.password, user.passwordHash)) {
            // 计算本次失败后的累计次数
            val currentFailures = user.failedLoginAttempts + 1
            val lockUntil = if (currentFailures >= AuthConstants.MAX_LOGIN_ATTEMPTS) {
                kotlin.time.Clock.System.now()
                    .plus(kotlin.time.Duration.Companion.parseIsoString("PT${AuthConstants.LOCKOUT_DURATION_MINUTES}M"))
                    .toLocalDateTime(TimeZone.UTC)
            } else {
                null
            }
            userRepository.recordLoginFailure(user.id, lockUntil)

            if (lockUntil != null) {
                return AuthResult.Error(
                    AuthErrorCodes.ACCOUNT_LOCKED,
                    "登录失败次数过多，账户已锁定 ${AuthConstants.LOCKOUT_DURATION_MINUTES} 分钟"
                )
            }

            val remainingAttempts = AuthConstants.MAX_LOGIN_ATTEMPTS - currentFailures
            return AuthResult.Error(
                AuthErrorCodes.INVALID_CREDENTIALS,
                "用户名或密码错误，还剩 $remainingAttempts 次尝试机会"
            )
        }

        // 5. 登录成功：重置失败计数
        userRepository.updateLoginSuccess(user.id)

        // 6. 获取用户角色
        val roles = getUserRoles(user.id)

        // 7. 生成 Token（refreshToken 为明文 JWT，稍后存 SHA-256 哈希）
        val accessToken = jwtService.generateAccessToken(user.id, user.username, roles)
        val refreshTokenRaw = jwtService.generateRefreshToken(user.id, request.rememberMe)

        // 8. 计算过期时间并将哈希值持久化
        val expiresAt = kotlin.time.Clock.System.now()
            .plus(
                if (request.rememberMe)
                    kotlin.time.Duration.Companion.parseIsoString("PT${AuthConstants.REMEMBER_ME_EXPIRY_SECONDS}S")
                else
                    kotlin.time.Duration.Companion.parseIsoString("PT${AuthConstants.REFRESH_TOKEN_EXPIRY_SECONDS}S")
            )
            .toLocalDateTime(TimeZone.UTC)

        tokenRepository.create(
            userId = user.id,
            tokenHash = sha256(refreshTokenRaw),
            expiresAt = expiresAt,
            deviceInfo = deviceInfo,
            ipAddress = ipAddress
        )

        // 9. 返回登录响应（refreshToken 为明文，由客户端通过 HttpOnly Cookie 持有）
        val response = LoginResponse(
            accessToken = accessToken,
            refreshToken = refreshTokenRaw,
            expiresIn = AuthConstants.ACCESS_TOKEN_EXPIRY_SECONDS,
            user = user.toUserProfile(roles)
        )

        return AuthResult.Success(response)
    }

    /**
     * 用户注册（仅账号密码方式）
     *
     * @param request 注册请求（username、password、nickname 可选）
     * @return 注册结果
     */
    suspend fun register(request: RegisterRequest): AuthResult<RegisterResponse> {
        // 1. 验证用户名格式
        if (request.username.length < AuthConstants.USERNAME_MIN_LENGTH ||
            request.username.length > AuthConstants.USERNAME_MAX_LENGTH
        ) {
            return AuthResult.Error(
                AuthErrorCodes.INVALID_CREDENTIALS,
                "用户名长度必须在 ${AuthConstants.USERNAME_MIN_LENGTH}-${AuthConstants.USERNAME_MAX_LENGTH} 之间"
            )
        }

        // 2. 检查用户名是否已存在
        if (userRepository.existsByUsername(request.username)) {
            return AuthResult.Error(AuthErrorCodes.USER_EXISTS, "用户名已被使用")
        }

        // 3. 验证密码强度
        val passwordValidation = PasswordService.validatePasswordStrength(request.password)
        if (!passwordValidation.isValid) {
            return AuthResult.Error(AuthErrorCodes.PASSWORD_WEAK, passwordValidation.getErrorMessage())
        }

        // 4. 加密密码并创建用户
        val passwordHash = PasswordService.hashPassword(request.password)
        val newUser = userRepository.create(
            username = request.username,
            passwordHash = passwordHash,
            nickname = request.nickname
        )

        val response = RegisterResponse(
            userId = newUser.id.toString(),
            message = "注册成功"
        )

        return AuthResult.Success(response)
    }

    /**
     * 刷新 Access Token
     *
     * @param refreshTokenRaw 客户端持有的明文 Refresh Token（来自 Cookie）
     * @return 新的 Access Token 及其有效期（秒）
     */
    suspend fun refreshAccessToken(refreshTokenRaw: String): AuthResult<Pair<String, Long>> {
        // 1. 验证 Refresh Token 签名与格式
        val verificationResult = jwtService.verifyRefreshToken(refreshTokenRaw)
        if (!verificationResult.isValid()) {
            val error = (verificationResult as TokenVerificationResult.Invalid).error
            return AuthResult.Error(AuthErrorCodes.TOKEN_INVALID, error)
        }

        // 2. 通过 SHA-256 哈希在数据库中查找令牌记录
        val tokenHash = sha256(refreshTokenRaw)
        val tokenRecord = tokenRepository.findByTokenHash(tokenHash)
            ?: return AuthResult.Error(AuthErrorCodes.TOKEN_REVOKED, "Token 已失效")

        // 3. 检查令牌是否已撤销
        if (tokenRecord.isRevoked) {
            return AuthResult.Error(AuthErrorCodes.TOKEN_REVOKED, "Token 已失效")
        }

        // 4. 提取用户 ID 并校验用户状态
        val decodedJWT = (verificationResult as TokenVerificationResult.Valid).decodedJWT
        val userId = jwtService.extractUserId(decodedJWT)
            ?: return AuthResult.Error(AuthErrorCodes.TOKEN_INVALID, "无法解析用户 ID")

        val user = userRepository.findById(userId)
            ?: return AuthResult.Error(AuthErrorCodes.USER_NOT_FOUND, "用户不存在")

        if (!user.isActive) {
            return AuthResult.Error(AuthErrorCodes.ACCOUNT_DISABLED, "账户已被禁用")
        }

        // 5. 生成新的 Access Token
        val roles = getUserRoles(userId)
        val newAccessToken = jwtService.generateAccessToken(userId, user.username, roles)

        return AuthResult.Success(newAccessToken to AuthConstants.ACCESS_TOKEN_EXPIRY_SECONDS)
    }

    /**
     * 用户登出（单设备）
     *
     * @param refreshTokenRaw 当前设备持有的明文 Refresh Token，用于定向撤销
     * @param userId 当前用户 ID
     */
    suspend fun logout(
        refreshTokenRaw: String?,
        userId: UUID
    ): AuthResult<Unit> {
        if (refreshTokenRaw != null) {
            tokenRepository.revokeByTokenHash(sha256(refreshTokenRaw))
        }
        return AuthResult.Success(Unit)
    }

    /**
     * 用户登出所有设备
     *
     * @param userId 当前用户 ID
     */
    suspend fun logoutAll(userId: UUID): AuthResult<Unit> {
        tokenRepository.revokeAllByUserId(userId)
        return AuthResult.Success(Unit)
    }

    /**
     * 修改密码（成功后撤销所有设备 Token，要求重新登录）
     *
     * @param userId 当前用户 ID
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    suspend fun changePassword(
        userId: UUID,
        oldPassword: String,
        newPassword: String
    ): AuthResult<Unit> {
        // 1. 查找用户
        val user = userRepository.findById(userId)
            ?: return AuthResult.Error(AuthErrorCodes.USER_NOT_FOUND, "用户不存在")

        // 2. 验证旧密码
        if (!PasswordService.verifyPassword(oldPassword, user.passwordHash)) {
            return AuthResult.Error(AuthErrorCodes.PASSWORD_INCORRECT, "旧密码错误")
        }

        // 3. 验证新密码强度
        val validation = PasswordService.validatePasswordStrength(newPassword)
        if (!validation.isValid) {
            return AuthResult.Error(AuthErrorCodes.PASSWORD_WEAK, validation.getErrorMessage())
        }

        // 4. 加密新密码并持久化
        val newHash = PasswordService.hashPassword(newPassword)
        userRepository.updatePassword(userId, newHash)

        // 5. 撤销所有 Token，要求重新登录
        tokenRepository.revokeAllByUserId(userId)

        return AuthResult.Success(Unit)
    }

    /**
     * 获取当前用户信息
     *
     * @param userId 当前用户 ID
     */
    suspend fun getCurrentUser(userId: UUID): AuthResult<UserProfile> {
        val user = userRepository.findById(userId)
            ?: return AuthResult.Error(AuthErrorCodes.USER_NOT_FOUND, "用户不存在")

        val roles = getUserRoles(userId)
        return AuthResult.Success(user.toUserProfile(roles))
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取用户角色列表
     * TODO: 未来从数据库查询，目前默认返回 VIEWER
     */
    private fun getUserRoles(@Suppress("UNUSED_PARAMETER") userId: UUID): List<UserRole> =
        listOf(UserRole.VIEWER)

    /**
     * 获取账户剩余锁定时间（分钟）
     */
    private fun getRemainingLockMinutes(user: UserModel): Long {
        val lockedUntil = user.lockedUntil ?: return 0
        val now = kotlin.time.Clock.System.now()
        val lockedInstant = kotlinx.datetime.Instant.parse(lockedUntil.toString() + "Z")
        val diff = lockedInstant.epochSeconds - now.epochSeconds
        return if (diff > 0) diff / 60 else 0
    }

    /**
     * 将 UserModel 转为 API 层 UserProfile
     * email 和 avatar 字段在新模型中不存在，固定为 null
     */
    private fun UserModel.toUserProfile(roles: List<UserRole>): UserProfile = UserProfile(
        id = id.toString(),
        username = username,
        nickname = nickname,
        avatar = null,
        roles = roles,
        createdAt = createdAt.toString()
    )

    /**
     * 检查 UserModel 是否处于锁定状态
     */
    private fun UserModel.isLocked(): Boolean {
        val locked = lockedUntil ?: return false
        val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return locked > now
    }

    companion object {
        /**
         * 计算字符串的 SHA-256 十六进制摘要
         */
        fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 认证结果密封类
 */
sealed class AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>()
    data class Error(val code: String, val message: String) : AuthResult<Nothing>()

    fun isSuccess(): Boolean = this is Success
    fun isError(): Boolean = this is Error
}

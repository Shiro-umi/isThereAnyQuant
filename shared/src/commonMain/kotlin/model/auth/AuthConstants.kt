package model.auth

/**
 * 认证相关常量
 */
object AuthConstants {
    
    /** HTTP Header 中 Token 的前缀 */
    const val TOKEN_PREFIX = "Bearer "
    
    /** HTTP Header 名称 */
    const val AUTHORIZATION_HEADER = "Authorization"
    
    /** Refresh Token Cookie 名称 */
    const val REFRESH_TOKEN_COOKIE = "refresh_token"
    
    /** Access Token 默认过期时间（2小时，单位：秒） */
    const val ACCESS_TOKEN_EXPIRY_SECONDS = 7200L
    
    /** Refresh Token 默认过期时间（7天，单位：秒） */
    const val REFRESH_TOKEN_EXPIRY_SECONDS = 604800L
    
    /** 记住我模式的 Refresh Token 过期时间（30天，单位：秒） */
    const val REMEMBER_ME_EXPIRY_SECONDS = 2592000L
    
    /** 密码最小长度 */
    const val PASSWORD_MIN_LENGTH = 8
    
    /** 用户名最小长度 */
    const val USERNAME_MIN_LENGTH = 3
    
    /** 用户名最大长度 */
    const val USERNAME_MAX_LENGTH = 64
    
    /** 最大登录失败次数 */
    const val MAX_LOGIN_ATTEMPTS = 5
    
    /** 账户锁定时间（15分钟，单位：分钟） */
    const val LOCKOUT_DURATION_MINUTES = 15
}

/**
 * 认证错误码
 */
object AuthErrorCodes {
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val USER_NOT_FOUND = "USER_NOT_FOUND"
    const val USER_EXISTS = "USER_EXISTS"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val TOKEN_INVALID = "TOKEN_INVALID"
    const val TOKEN_REVOKED = "TOKEN_REVOKED"
    const val PASSWORD_WEAK = "PASSWORD_WEAK"
    const val PASSWORD_INCORRECT = "PASSWORD_INCORRECT"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
    const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
}

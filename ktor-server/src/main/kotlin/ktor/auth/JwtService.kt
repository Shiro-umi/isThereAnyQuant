package ktor.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import model.auth.AuthConstants
import model.auth.UserRole
import java.util.Date
import java.util.UUID

/**
 * JWT 服务
 * 负责生成和验证 JWT Token
 */
class JwtService(
    private val secret: String,
    private val issuer: String,
    private val audience: String
) {
    
    /** JWT 算法 */
    private val algorithm = Algorithm.HMAC256(secret)
    
    /** Access Token 验证器 */
    val accessTokenVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()
    
    /** Refresh Token 验证器 */
    val refreshTokenVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .build()
    
    /**
     * 生成 Access Token
     * @param userId 用户 ID
     * @param username 用户名
     * @param roles 用户角色列表
     * @return JWT Token 字符串
     */
    fun generateAccessToken(
        userId: UUID,
        username: String,
        roles: List<UserRole>
    ): String {
        val now = Date()
        val expiry = Date(now.time + AuthConstants.ACCESS_TOKEN_EXPIRY_SECONDS * 1000)
        
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId.toString())
            .withClaim("userId", userId.toString())
            .withClaim("username", username)
            .withClaim("roles", roles.map { it.name })
            .withClaim("tokenType", "access")
            .withIssuedAt(now)
            .withExpiresAt(expiry)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)
    }
    
    /**
     * 生成 Refresh Token
     * @param userId 用户 ID
     * @param rememberMe 是否记住我（延长过期时间）
     * @return JWT Token 字符串
     */
    fun generateRefreshToken(
        userId: UUID,
        rememberMe: Boolean = false
    ): String {
        val now = Date()
        val expirySeconds = if (rememberMe) {
            AuthConstants.REMEMBER_ME_EXPIRY_SECONDS
        } else {
            AuthConstants.REFRESH_TOKEN_EXPIRY_SECONDS
        }
        val expiry = Date(now.time + expirySeconds * 1000)
        
        return JWT.create()
            .withIssuer(issuer)
            .withSubject(userId.toString())
            .withClaim("userId", userId.toString())
            .withClaim("tokenType", "refresh")
            .withClaim("rememberMe", rememberMe)
            .withIssuedAt(now)
            .withExpiresAt(expiry)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)
    }
    
    /**
     * 验证 Access Token
     * @param token JWT Token 字符串
     * @return 验证结果
     */
    fun verifyAccessToken(token: String): TokenVerificationResult {
        return try {
            val decoded = accessTokenVerifier.verify(token)
            
            // 检查 Token 类型
            val tokenType = decoded.getClaim("tokenType")?.asString()
            if (tokenType != "access") {
                return TokenVerificationResult.Invalid("Token 类型不正确")
            }
            
            TokenVerificationResult.Valid(decoded)
        } catch (e: JWTVerificationException) {
            TokenVerificationResult.Invalid(e.message ?: "Token 验证失败")
        }
    }
    
    /**
     * 验证 Refresh Token
     * @param token JWT Token 字符串
     * @return 验证结果
     */
    fun verifyRefreshToken(token: String): TokenVerificationResult {
        return try {
            val decoded = refreshTokenVerifier.verify(token)
            
            // 检查 Token 类型
            val tokenType = decoded.getClaim("tokenType")?.asString()
            if (tokenType != "refresh") {
                return TokenVerificationResult.Invalid("Token 类型不正确")
            }
            
            TokenVerificationResult.Valid(decoded)
        } catch (e: JWTVerificationException) {
            TokenVerificationResult.Invalid(e.message ?: "Token 验证失败")
        }
    }
    
    /**
     * 从 Token 中提取用户 ID
     * @param decodedJWT 已解码的 JWT
     * @return 用户 ID
     */
    fun extractUserId(decodedJWT: DecodedJWT): UUID? {
        return try {
            UUID.fromString(decodedJWT.getClaim("userId")?.asString())
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 从 Token 中提取用户名
     * @param decodedJWT 已解码的 JWT
     * @return 用户名
     */
    fun extractUsername(decodedJWT: DecodedJWT): String? {
        return decodedJWT.getClaim("username")?.asString()
    }
    
    /**
     * 从 Token 中提取角色列表
     * @param decodedJWT 已解码的 JWT
     * @return 角色列表
     */
    fun extractRoles(decodedJWT: DecodedJWT): List<UserRole> {
        val rolesClaim = decodedJWT.getClaim("roles")?.asList(String::class.java)
        return rolesClaim?.mapNotNull { roleName ->
            try {
                UserRole.valueOf(roleName)
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: emptyList()
    }
    
    /**
     * 获取 Token 过期时间（毫秒时间戳）
     * @param decodedJWT 已解码的 JWT
     * @return 过期时间戳
     */
    fun extractExpiration(decodedJWT: DecodedJWT): Long {
        return decodedJWT.expiresAt?.time ?: 0
    }
    
    /**
     * 计算 Token 剩余有效时间（秒）
     * @param decodedJWT 已解码的 JWT
     * @return 剩余秒数
     */
    fun getRemainingSeconds(decodedJWT: DecodedJWT): Long {
        val expiration = extractExpiration(decodedJWT)
        val now = System.currentTimeMillis()
        return if (expiration > now) (expiration - now) / 1000 else 0
    }
}

/**
 * Token 验证结果
 */
sealed class TokenVerificationResult {
    /**
     * 验证成功
     * @param decodedJWT 已解码的 JWT
     */
    data class Valid(val decodedJWT: DecodedJWT) : TokenVerificationResult()
    
    /**
     * 验证失败
     * @param error 错误信息
     */
    data class Invalid(val error: String) : TokenVerificationResult()
    
    /**
     * 是否验证成功
     */
    fun isValid(): Boolean = this is Valid
}

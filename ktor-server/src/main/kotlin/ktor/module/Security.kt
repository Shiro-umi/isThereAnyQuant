package ktor.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import ktor.auth.JwtService
import ktor.auth.UserPrincipal
import model.auth.AuthErrorCodes
import model.auth.AuthErrorResponse
import org.shiroumi.config.ConfigHolder
import org.shiroumi.database.user.createRefreshTokenRepository
import org.shiroumi.database.user.createUserRepository
import org.shiroumi.database.user.repository.RefreshTokenRepository
import org.shiroumi.database.user.repository.UserRepository

/**
 * 配置 Ktor 安全模块
 * 包括 JWT 认证和 CORS
 */
fun Application.configureSecurity() {
    val config = ConfigHolder.config
    
    // 初始化 JWT 服务
    val jwtService = JwtService(
        secret = config.auth.jwt.secret,
        issuer = config.auth.jwt.issuer,
        audience = config.auth.jwt.audience
    )
    
    // 安装 JWT 认证
    install(Authentication) {
        jwt("auth-jwt") {
            realm = config.auth.jwt.realm
            
            verifier(jwtService.accessTokenVerifier)
            
            validate { credential ->
                val userId = credential.payload.getClaim("userId")?.asString()
                val username = credential.payload.getClaim("username")?.asString()
                val roles = credential.payload.getClaim("roles")?.asList(String::class.java)
                
                if (userId != null && username != null) {
                    UserPrincipal(
                        userId = java.util.UUID.fromString(userId),
                        username = username,
                        roles = roles ?: emptyList()
                    )
                } else {
                    null
                }
            }
            
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(
                        error = AuthErrorCodes.TOKEN_INVALID,
                        message = "Token 无效或已过期"
                    )
                )
            }
        }
    }
    
    // 安装 CORS - 使用配置文件
    val corsConfig = config.auth.cors
    // 将 allowedOrigins 中的 "auto-lan" 占位符替换为实际检测到的局域网 IP
    val resolvedOrigins = corsConfig.allowedOrigins.map { origin ->
        if (origin == "auto-lan") {
            "${config.server.publicScheme}://${config.server.publicHost}:${config.server.publicPort}"
        } else {
            origin
        }
    }
    install(CORS) {
        // 允许任何来源（配合 allowCredentials 使用时需要指定具体来源）
        // 使用自定义逻辑处理同域和跨域情况
        allowOrigins { origin ->
            // 同域请求：浏览器可能不发送 Origin 或发送相同域名
            // 跨域请求：检查是否在允许列表中
            origin.isEmpty() || resolvedOrigins.any { allowed ->
                if (allowed.contains("*")) {
                    val regex = allowed.replace("*", ".*").toRegex()
                    regex.matches(origin)
                } else {
                    origin == allowed
                }
            }
        }
        corsConfig.allowedMethods.forEach { method ->
            allowMethod(HttpMethod.parse(method))
        }
        corsConfig.allowedHeaders.forEach { header ->
            allowHeader(header)
        }
        allowCredentials = corsConfig.allowCredentials
        maxAgeInSeconds = corsConfig.maxAge.toLong()
    }
    
    // 存储 JwtService 到应用属性，供路由使用
    attributes.put(jwtServiceKey, jwtService)

    // 初始化 UserRepository / RefreshTokenRepository（注入 commonDb），存储到应用属性
    val userRepo = createUserRepository()
    val tokenRepo = createRefreshTokenRepository()
    attributes.put(userRepositoryKey, userRepo)
    attributes.put(refreshTokenRepositoryKey, tokenRepo)
}

/**
 * JwtService 的 AttributeKey
 */
val jwtServiceKey = io.ktor.util.AttributeKey<JwtService>("JwtService")

/**
 * UserRepository 的 AttributeKey
 */
val userRepositoryKey = io.ktor.util.AttributeKey<UserRepository>("UserRepository")

/**
 * RefreshTokenRepository 的 AttributeKey
 */
val refreshTokenRepositoryKey = io.ktor.util.AttributeKey<RefreshTokenRepository>("RefreshTokenRepository")

/**
 * 获取 JwtService 扩展属性
 */
val Application.jwtService: JwtService
    get() = attributes[jwtServiceKey]

/**
 * 获取 UserRepository 扩展属性
 */
val Application.userRepository: UserRepository
    get() = attributes[userRepositoryKey]

/**
 * 获取 RefreshTokenRepository 扩展属性
 */
val Application.refreshTokenRepository: RefreshTokenRepository
    get() = attributes[refreshTokenRepositoryKey]

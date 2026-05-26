package ktor.auth

import io.ktor.server.auth.Principal
import java.util.UUID

/**
 * Ktor 用户身份凭证
 * 用于存储 JWT 验证后的用户信息
 */
data class UserPrincipal(
    val userId: UUID,
    val username: String,
    val roles: List<String>
) : Principal

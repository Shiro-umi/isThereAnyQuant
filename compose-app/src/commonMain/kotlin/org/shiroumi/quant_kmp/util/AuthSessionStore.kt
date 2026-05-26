package org.shiroumi.quant_kmp.util

import kotlinx.serialization.Serializable

@Serializable
data class AuthSession(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Long,
    val user: UserInfo?,
)

expect object PlatformAuthSessionStore {
    val storesRefreshToken: Boolean
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession)
    suspend fun clear()
}

package org.shiroumi.database.user.model

import kotlinx.datetime.LocalDateTime
import java.util.UUID

/**
 * 刷新令牌模型
 *
 * 对应 sys_refresh_token 表的数据类，存储令牌的 SHA-256 哈希值而非明文。
 *
 * @property id 令牌唯一标识符（UUID）
 * @property userId 关联用户 ID
 * @property tokenHash 令牌的 SHA-256 哈希值
 * @property expiresAt 令牌过期时间
 * @property createdAt 令牌创建时间
 * @property deviceInfo 设备信息（User-Agent 等），可为 null
 * @property ipAddress 请求来源 IP 地址，可为 null
 * @property isRevoked 是否已被撤销
 * @property revokedAt 撤销时间，null 表示尚未撤销
 */
data class RefreshTokenModel(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val deviceInfo: String?,
    val ipAddress: String?,
    val isRevoked: Boolean,
    val revokedAt: LocalDateTime?,
)

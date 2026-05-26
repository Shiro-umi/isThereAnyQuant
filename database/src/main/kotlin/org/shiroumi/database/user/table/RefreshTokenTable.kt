package org.shiroumi.database.user.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * 刷新令牌表（common_db）
 * 表名: sys_refresh_token
 *
 * 存储 JWT 刷新令牌的 SHA-256 哈希值（不存储明文），支持多设备登录管理。
 */
object RefreshTokenTable : UUIDTable("sys_refresh_token") {

    /** 关联用户 ID（外键 -> sys_user.id） */
    val userId = reference("user_id", UserTable)

    /** 令牌的 SHA-256 哈希值 - 唯一索引，不存明文 */
    val tokenHash = varchar("token_hash", 128).uniqueIndex()

    /** 令牌过期时间 */
    val expiresAt = datetime("expires_at")

    /** 令牌创建时间 */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** 设备信息（User-Agent 等），可为空 */
    val deviceInfo = varchar("device_info", 512).nullable()

    /** 请求来源 IP 地址，可为空 */
    val ipAddress = varchar("ip_address", 64).nullable()

    /** 是否已被撤销（登出时标记） */
    val isRevoked = bool("is_revoked").default(false)

    /** 撤销时间，null 表示尚未撤销 */
    val revokedAt = datetime("revoked_at").nullable()
}

package org.shiroumi.database.user.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.user.model.RefreshTokenModel
import org.shiroumi.database.user.table.RefreshTokenTable
import java.util.UUID

/**
 * 刷新令牌数据访问层（common_db）
 *
 * 令牌以 SHA-256 哈希形式存储，明文由调用方在传入前完成哈希计算。
 * 所有操作通过构造函数注入的 [db] 实例执行，不依赖任何全局数据库变量。
 *
 * @param db 目标数据库连接实例（应为 commonDb）
 */
class RefreshTokenRepository(private val db: Database) {

    /**
     * 确保 sys_refresh_token 表已创建（幂等）
     */
    suspend fun ensureSchema() = newSuspendedTransaction(Dispatchers.IO, db) {
        SchemaUtils.createMissingTablesAndColumns(RefreshTokenTable)
    }

    /**
     * 持久化一条新的刷新令牌记录
     *
     * @param userId 关联用户 UUID
     * @param tokenHash 令牌的 SHA-256 哈希值（不接受明文）
     * @param expiresAt 令牌过期时间
     * @param deviceInfo 设备信息（User-Agent 等），可为 null
     * @param ipAddress 请求来源 IP 地址，可为 null
     * @return 持久化后的令牌完整模型
     */
    suspend fun create(
        userId: UUID,
        tokenHash: String,
        expiresAt: LocalDateTime,
        deviceInfo: String?,
        ipAddress: String?,
    ): RefreshTokenModel = newSuspendedTransaction(Dispatchers.IO, db) {
        val insertedId = RefreshTokenTable.insertAndGetId {
            it[RefreshTokenTable.userId] = userId
            it[RefreshTokenTable.tokenHash] = tokenHash
            it[RefreshTokenTable.expiresAt] = expiresAt
            it[RefreshTokenTable.deviceInfo] = deviceInfo
            it[RefreshTokenTable.ipAddress] = ipAddress
            it[RefreshTokenTable.isRevoked] = false
            it[RefreshTokenTable.revokedAt] = null
        }
        RefreshTokenTable.selectAll()
            .where { RefreshTokenTable.id eq insertedId }
            .single()
            .toRefreshTokenModel()
    }

    /**
     * 根据令牌哈希值查找令牌记录
     *
     * @param tokenHash 令牌的 SHA-256 哈希值
     * @return 匹配的令牌模型，不存在返回 null
     */
    suspend fun findByTokenHash(tokenHash: String): RefreshTokenModel? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.tokenHash eq tokenHash }
                .firstOrNull()
                ?.toRefreshTokenModel()
        }

    /**
     * 撤销指定哈希值对应的令牌（标记为已撤销，不物理删除）
     *
     * @param tokenHash 令牌的 SHA-256 哈希值
     */
    suspend fun revokeByTokenHash(tokenHash: String): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            RefreshTokenTable.update({ RefreshTokenTable.tokenHash eq tokenHash }) {
                it[isRevoked] = true
                it[revokedAt] = now
            }
        }

    /**
     * 撤销指定用户的全部未撤销令牌（用于强制登出所有设备）
     *
     * @param userId 用户 UUID
     */
    suspend fun revokeAllByUserId(userId: UUID): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            RefreshTokenTable.update({
                (RefreshTokenTable.userId eq userId) and
                (RefreshTokenTable.isRevoked eq false)
            }) {
                it[isRevoked] = true
                it[revokedAt] = now
            }
        }

    /**
     * 物理删除所有已过期或已撤销的令牌记录（定时清理任务使用）
     *
     * 过期判断基于数据库服务器时间，等价于 expires_at < NOW()。
     * 已撤销记录无论是否过期均一并清理，减少存储占用。
     */
    suspend fun deleteExpired(): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            RefreshTokenTable.deleteWhere {
                (RefreshTokenTable.expiresAt less CurrentDateTime) or
                (RefreshTokenTable.isRevoked eq true)
            }
        }

    // ==================== 私有扩展函数 ====================

    private fun ResultRow.toRefreshTokenModel(): RefreshTokenModel = RefreshTokenModel(
        id = this[RefreshTokenTable.id].value,
        userId = this[RefreshTokenTable.userId].value,
        tokenHash = this[RefreshTokenTable.tokenHash],
        expiresAt = this[RefreshTokenTable.expiresAt],
        createdAt = this[RefreshTokenTable.createdAt],
        deviceInfo = this[RefreshTokenTable.deviceInfo],
        ipAddress = this[RefreshTokenTable.ipAddress],
        isRevoked = this[RefreshTokenTable.isRevoked],
        revokedAt = this[RefreshTokenTable.revokedAt],
    )
}

package org.shiroumi.database.user.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.user.model.UserModel
import org.shiroumi.database.user.table.UserTable
import java.util.UUID

/**
 * 用户数据访问层（common_db）
 *
 * 所有操作通过构造函数注入的 [db] 实例执行，不依赖任何全局数据库变量。
 * 协程挂起版本，使用 [Dispatchers.IO] 避免阻塞主线程。
 *
 * @param db 目标数据库连接实例（应为 commonDb）
 */
class UserRepository(private val db: Database) {

    /**
     * 确保 sys_user 表已创建（幂等）
     */
    suspend fun ensureSchema() = newSuspendedTransaction(Dispatchers.IO, db) {
        SchemaUtils.create(UserTable)
    }

    /**
     * 根据用户名查找用户
     *
     * @param username 登录账号
     * @return 匹配的用户模型，不存在返回 null
     */
    suspend fun findByUsername(username: String): UserModel? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserTable.selectAll()
                .where { UserTable.username eq username }
                .firstOrNull()
                ?.toUserModel()
        }

    /**
     * 根据 ID 查找用户
     *
     * @param id 用户 UUID
     * @return 匹配的用户模型，不存在返回 null
     */
    suspend fun findById(id: UUID): UserModel? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserTable.selectAll()
                .where { UserTable.id eq id }
                .firstOrNull()
                ?.toUserModel()
        }

    /**
     * 创建新用户
     *
     * @param username 登录账号（必须全局唯一）
     * @param passwordHash BCrypt 加密后的密码哈希
     * @param nickname 显示名称，可为 null
     * @return 创建后的用户完整模型
     */
    suspend fun create(
        username: String,
        passwordHash: String,
        nickname: String?,
    ): UserModel = newSuspendedTransaction(Dispatchers.IO, db) {
        val insertedId = UserTable.insertAndGetId {
            it[UserTable.username] = username
            it[UserTable.passwordHash] = passwordHash
            it[UserTable.nickname] = nickname
            it[UserTable.isActive] = true
            it[UserTable.failedLoginAttempts] = 0
        }
        UserTable.selectAll()
            .where { UserTable.id eq insertedId }
            .single()
            .toUserModel()
    }

    /**
     * 登录成功后重置失败计数并更新 updatedAt
     *
     * @param userId 用户 UUID
     */
    suspend fun updateLoginSuccess(userId: UUID): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            UserTable.update({ UserTable.id eq userId }) {
                it[failedLoginAttempts] = 0
                it[lockedUntil] = null
                it[updatedAt] = now
            }
        }

    /**
     * 记录一次登录失败，失败计数 +1；若 [lockUntil] 不为 null 则同时设置锁定截止时间
     *
     * @param userId 用户 UUID
     * @param lockUntil 锁定截止时间，null 表示本次不触发锁定
     */
    suspend fun recordLoginFailure(userId: UUID, lockUntil: LocalDateTime?): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val current = UserTable.selectAll()
                .where { UserTable.id eq userId }
                .firstOrNull()
                ?.get(UserTable.failedLoginAttempts) ?: 0

            UserTable.update({ UserTable.id eq userId }) {
                it[failedLoginAttempts] = current + 1
                it[lockedUntil] = lockUntil
                it[updatedAt] = now
            }
        }

    /**
     * 检查用户名是否已被注册
     *
     * @param username 待检查的用户名
     * @return true 表示已存在
     */
    suspend fun existsByUsername(username: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserTable.selectAll()
                .where { UserTable.username eq username }
                .count() > 0
        }

    /**
     * 更新用户密码哈希（修改密码场景使用）
     *
     * @param userId 用户 UUID
     * @param newPasswordHash 新的 BCrypt 密码哈希
     */
    suspend fun updatePassword(userId: UUID, newPasswordHash: String): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            UserTable.update({ UserTable.id eq userId }) {
                it[passwordHash] = newPasswordHash
                it[updatedAt] = now
            }
        }

    /**
     * 获取持仓跟踪最早跟随日校准（yyyy-MM-dd）
     *
     * @param userId 用户 UUID
     * @return 最早跟随日，null 表示跟随模型完整持仓流
     */
    suspend fun getTrackingFollowStartDate(userId: UUID): String? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserTable.selectAll()
                .where { UserTable.id eq userId }
                .firstOrNull()
                ?.get(UserTable.trackingFollowStartDate)
        }

    /**
     * 设置持仓跟踪最早跟随日校准（yyyy-MM-dd）
     *
     * @param userId 用户 UUID
     * @param followStartDate 最早跟随日，null 表示跟随模型完整持仓流
     */
    suspend fun setTrackingFollowStartDate(userId: UUID, followStartDate: String?) {
        newSuspendedTransaction(Dispatchers.IO, db) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            UserTable.update({ UserTable.id eq userId }) {
                it[trackingFollowStartDate] = followStartDate
                it[updatedAt] = now
            }
        }
    }

    // ==================== 私有扩展函数 ====================

    private fun ResultRow.toUserModel(): UserModel = UserModel(
        id = this[UserTable.id].value,
        username = this[UserTable.username],
        passwordHash = this[UserTable.passwordHash],
        nickname = this[UserTable.nickname],
        isActive = this[UserTable.isActive],
        failedLoginAttempts = this[UserTable.failedLoginAttempts],
        lockedUntil = this[UserTable.lockedUntil],
        trackingFollowStartDate = this[UserTable.trackingFollowStartDate],
        createdAt = this[UserTable.createdAt],
        updatedAt = this[UserTable.updatedAt],
    )
}

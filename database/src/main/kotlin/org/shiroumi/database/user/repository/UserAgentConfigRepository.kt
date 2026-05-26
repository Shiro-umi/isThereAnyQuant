package org.shiroumi.database.user.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.user.model.UserAgentConfigModel
import org.shiroumi.database.user.model.toUserAgentConfigModel
import org.shiroumi.database.user.table.UserAgentConfigTable
import java.util.UUID

/**
 * 用户 Agent 配置数据访问层（common_db）
 *
 * @param db 目标数据库连接实例（应为 commonDb）
 */
class UserAgentConfigRepository(private val db: Database) {

    /**
     * 确保 sys_user_agent_config 表已创建（幂等）
     */
    suspend fun ensureSchema() = newSuspendedTransaction(Dispatchers.IO, db) {
        SchemaUtils.createMissingTablesAndColumns(UserAgentConfigTable)
    }

    /**
     * 根据用户 ID 查询 Agent 配置
     *
     * @param userId 关联用户 UUID
     * @return 关联的 Agent 配置模型，不存在则返回 null
     */
    suspend fun findByUserId(userId: UUID): UserAgentConfigModel? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserAgentConfigTable.selectAll()
                .where { UserAgentConfigTable.userId eq userId }
                .firstOrNull()
                ?.toUserAgentConfigModel()
        }

    /**
     * 为用户创建或覆盖 Agent 配置（Upsert 逻辑依赖先查后插以保持兼容，也可调整）
     * 由于我们对 userId 设置了唯一索引，可以直接更新或插入。
     * 
     * @param userId 关联的用户 UUID
     * @param workDir 隔离沙盒的工作目录对应的绝对路径或相对路径
     * @param isolated 是否开启配置隔离
     * @param allowedSkills 指定的白名单 Skill（逗号分隔，如 "trend-analysis,risk-assessment"）
     */
    suspend fun saveOrUpdate(
        userId: UUID,
        workDir: String,
        isolated: Boolean = true,
        allowedSkills: String? = null,
    ): UserAgentConfigModel = newSuspendedTransaction(Dispatchers.IO, db) {
        val existing = UserAgentConfigTable.selectAll()
            .where { UserAgentConfigTable.userId eq userId }
            .firstOrNull()

        if (existing == null) {
            val insertedId = UserAgentConfigTable.insertAndGetId {
                it[UserAgentConfigTable.userId] = userId
                it[UserAgentConfigTable.workDir] = workDir
                it[UserAgentConfigTable.isolated] = isolated
                it[UserAgentConfigTable.allowedSkills] = allowedSkills
            }
            UserAgentConfigTable.selectAll()
                .where { UserAgentConfigTable.id eq insertedId }
                .single()
                .toUserAgentConfigModel()
        } else {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            UserAgentConfigTable.update({ UserAgentConfigTable.userId eq userId }) {
                it[UserAgentConfigTable.workDir] = workDir
                it[UserAgentConfigTable.isolated] = isolated
                it[UserAgentConfigTable.allowedSkills] = allowedSkills
                it[UserAgentConfigTable.updatedAt] = now
            }
            UserAgentConfigTable.selectAll()
                .where { UserAgentConfigTable.userId eq userId }
                .single()
                .toUserAgentConfigModel()
        }
    }

    suspend fun saveModelConfig(
        userId: UUID,
        workDir: String,
        isolated: Boolean,
        selectionMode: String,
        presetKey: String?,
        customDisplayName: String?,
        customModelId: String?,
        customBaseUrl: String?,
        customApiKeyEncrypted: String?,
        keepExistingApiKey: Boolean,
    ): UserAgentConfigModel = newSuspendedTransaction(Dispatchers.IO, db) {
        val existing = UserAgentConfigTable.selectAll()
            .where { UserAgentConfigTable.userId eq userId }
            .firstOrNull()
        val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)

        if (existing == null) {
            val insertedId = UserAgentConfigTable.insertAndGetId {
                it[UserAgentConfigTable.userId] = userId
                it[UserAgentConfigTable.workDir] = workDir
                it[UserAgentConfigTable.isolated] = isolated
                it[UserAgentConfigTable.modelSelectionMode] = selectionMode
                it[UserAgentConfigTable.modelPresetKey] = presetKey
                it[UserAgentConfigTable.customModelDisplayName] = customDisplayName
                it[UserAgentConfigTable.customModelId] = customModelId
                it[UserAgentConfigTable.customBaseUrl] = customBaseUrl
                it[UserAgentConfigTable.customApiKeyEncrypted] = customApiKeyEncrypted
            }
            UserAgentConfigTable.selectAll()
                .where { UserAgentConfigTable.id eq insertedId }
                .single()
                .toUserAgentConfigModel()
        } else {
            UserAgentConfigTable.update({ UserAgentConfigTable.userId eq userId }) {
                it[UserAgentConfigTable.modelSelectionMode] = selectionMode
                it[UserAgentConfigTable.modelPresetKey] = presetKey
                it[UserAgentConfigTable.customModelDisplayName] = customDisplayName
                it[UserAgentConfigTable.customModelId] = customModelId
                it[UserAgentConfigTable.customBaseUrl] = customBaseUrl
                if (!keepExistingApiKey) {
                    it[UserAgentConfigTable.customApiKeyEncrypted] = customApiKeyEncrypted
                }
                it[UserAgentConfigTable.updatedAt] = now
            }
            UserAgentConfigTable.selectAll()
                .where { UserAgentConfigTable.userId eq userId }
                .single()
                .toUserAgentConfigModel()
        }
    }
}

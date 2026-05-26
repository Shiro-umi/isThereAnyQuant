package org.shiroumi.database.user.model

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.shiroumi.database.user.table.UserAgentConfigTable
import java.util.UUID

/**
 * 用户 Agent 配置模型（Domain / Entity）
 *
 * @property id 唯一标识符
 * @property userId 所属用户 ID
 * @property workDir 隔离沙盒的工作目录对应的绝对路径或相对路径
 * @property isolated 是否开启配置隔离
 * @property allowedSkills 允许使用的 Skill 列表逗号分隔字符串
 * @property createdAt 记录创建时间
 * @property updatedAt 记录最后更新时间
 */
data class UserAgentConfigModel(
    val id: UUID,
    val userId: UUID,
    val workDir: String,
    val isolated: Boolean,
    val allowedSkills: String?,
    val modelSelectionMode: String,
    val modelPresetKey: String?,
    val customModelDisplayName: String?,
    val customModelId: String?,
    val customBaseUrl: String?,
    val customApiKeyEncrypted: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    /**
     * 解析 allowedSkills 为列表集合
     */
    val parsedAllowedSkills: List<String>
        get() {
            val dbSkills = allowedSkills?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            return dbSkills.distinct()
        }
}

/**
 * 从 JetBrains Exposed 的 [ResultRow] 转换为域模型 [UserAgentConfigModel]
 */
internal fun ResultRow.toUserAgentConfigModel(): UserAgentConfigModel = UserAgentConfigModel(
    id = this[UserAgentConfigTable.id].value,
    userId = this[UserAgentConfigTable.userId].value,
    workDir = this[UserAgentConfigTable.workDir],
    isolated = this[UserAgentConfigTable.isolated],
    allowedSkills = this[UserAgentConfigTable.allowedSkills],
    modelSelectionMode = this[UserAgentConfigTable.modelSelectionMode],
    modelPresetKey = this[UserAgentConfigTable.modelPresetKey],
    customModelDisplayName = this[UserAgentConfigTable.customModelDisplayName],
    customModelId = this[UserAgentConfigTable.customModelId],
    customBaseUrl = this[UserAgentConfigTable.customBaseUrl],
    customApiKeyEncrypted = this[UserAgentConfigTable.customApiKeyEncrypted],
    createdAt = this[UserAgentConfigTable.createdAt],
    updatedAt = this[UserAgentConfigTable.updatedAt],
)

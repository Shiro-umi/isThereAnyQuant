package org.shiroumi.database.user.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.shiroumi.database.MAX_VARCHAR_LENGTH

/**
 * 用户 Agent 配置表（common_db）
 * 表名: sys_user_agent_config
 *
 * 存储每个用户独立的 Agent 配置，包括分配的隔离工作区与允许的话术技能列表。
 */
object UserAgentConfigTable : UUIDTable("sys_user_agent_config") {

    /** 关联的用户 ID - 必须保持唯一索引（每个用户仅有一条配置记录） */
    val userId = reference("user_id", UserTable).uniqueIndex()

    /** Agent 分配的隔离工作区绝对路径 */
    val workDir = varchar("work_dir", MAX_VARCHAR_LENGTH)

    /** 是否隔离全局配置（只读取项目独立的 .claude 设定以及沙盒内的 skills） */
    val isolated = bool("isolated").default(true)

    /** 该用户被授权使用的 Skill 名称列表，以逗号分隔，如 "entry-exit-analysis,trend-analysis" */
    val allowedSkills = varchar("allowed_skills", MAX_VARCHAR_LENGTH).nullable()

    /** Agent 模型选择模式：PRESET 或 CUSTOM */
    val modelSelectionMode = varchar("model_selection_mode", 32).default("PRESET")

    /** 选择的编译期预设模型 key */
    val modelPresetKey = varchar("model_preset_key", 128).nullable()

    /** 用户自定义模型显示名 */
    val customModelDisplayName = varchar("custom_model_display_name", 128).nullable()

    /** 用户自定义模型 ID */
    val customModelId = varchar("custom_model_id", 256).nullable()

    /** 用户自定义 API Base URL */
    val customBaseUrl = varchar("custom_base_url", MAX_VARCHAR_LENGTH).nullable()

    /** 加密后的用户自定义 API Key；API 层永不返回明文 */
    val customApiKeyEncrypted = text("custom_api_key_encrypted").nullable()

    /** 记录创建时间 */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** 记录最后更新时间 */
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}

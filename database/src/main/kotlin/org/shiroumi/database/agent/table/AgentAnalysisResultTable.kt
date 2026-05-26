package org.shiroumi.database.agent.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import org.shiroumi.database.MAX_VARCHAR_LENGTH
import org.shiroumi.database.user.table.UserTable

/**
 * Agent 分析结果持久化表（common_db）
 *
 * 表名: agent_analysis_result
 * 核心关联维度：用户 -> 股票 -> 分析类型 -> 时间
 *
 * 注意：本表仅存储 Agent 模型生成的分析结论（content_md），
 * 不存储用户原始 Prompt 文本，避免用户输入直接落库。
 */
object AgentAnalysisResultTable : UUIDTable("agent_analysis_result") {

    /** 关联用户 - CASCADE 删除 */
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)

    /** 股票代码（ts_code），如 000001.SZ */
    val tsCode = varchar("ts_code", 32).index()

    /** 分析类型编码，对应 AgentAnalysisType.code */
    val analysisType = varchar("analysis_type", 32).index()

    /** Agent 会话 ID，用于追溯 */
    val sessionId = varchar("session_id", 64).nullable().index()

    /** 标题：取 Markdown 内容第一行，便于列表展示 */
    val title = varchar("title", 256).nullable()

    /** Markdown 完整内容（进程启动时升级为 MEDIUMTEXT） */
    val contentMd = text("content_md")

    /** 扩展元数据 JSON（如关键价位、风险收益比等结构化提取） */
    val metadataJson = text("metadata_json").nullable()

    /** 关联交易日（可选） */
    val tradeDate = date("trade_date").nullable()

    /** 公开分享 token；非空即代表该记录已被分享，可被匿名访问 */
    val shareToken = varchar("share_token", 32).nullable().uniqueIndex()

    /** 首次生成分享 token 的时间 */
    val sharedAt = datetime("shared_at").nullable()

    /** 生成分享时刻 owner 选择的主题名（对齐 Compose AppColorTheme.name）；null 时分享页用默认主题 */
    val shareTheme = varchar("share_theme", 32).nullable()

    /** 生成分享时刻 owner 是否在 Dark 模式；null 时分享页用 Dark */
    val shareBrightnessDark = bool("share_brightness_dark").nullable()

    /** 记录创建时间 */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** 记录最后更新时间 */
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    init {
        index("idx_user_stock_type_time", isUnique = false, userId, tsCode, analysisType, createdAt)
        index("idx_user_time", isUnique = false, userId, createdAt)
    }
}

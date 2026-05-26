package org.shiroumi.database.agent.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.util.UUID

/**
 * Agent 分析结果领域模型
 *
 * 对应 agent_analysis_result 表的纯数据类，供 Repository 与业务层使用。
 *
 * 注意：contentMd 仅保存 Agent 模型输出的分析结论，不保存用户原始输入。
 */
data class AgentAnalysisResultModel(
    val id: UUID,
    val userId: UUID,
    val tsCode: String,
    val analysisType: String,
    val sessionId: String?,
    val title: String?,
    val contentMd: String,
    val metadataJson: String?,
    val tradeDate: LocalDate?,
    val shareToken: String? = null,
    val sharedAt: LocalDateTime? = null,
    val shareTheme: String? = null,
    val shareBrightnessDark: Boolean? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

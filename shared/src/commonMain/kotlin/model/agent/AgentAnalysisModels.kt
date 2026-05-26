package model.agent

import kotlinx.serialization.Serializable

/**
 * Agent 分析上下文
 *
 * 由前端随 Prompt 一并发送，用于服务端在持久化时关联股票、分析类型和交易日。
 */
@Serializable
data class AgentAnalysisContext(
    val tsCode: String? = null,
    val stockName: String? = null,
    val analysisType: String? = null,
    val tradeDate: String? = null
)

/**
 * Agent Prompt 请求包装体
 *
 * 通过 JSON 协议携带 Prompt 与可选分析上下文。
 */
@Serializable
data class AgentPromptRequest(
    val prompt: String,
    val context: AgentAnalysisContext? = null
)

/**
 * Agent 分析结果 DTO
 *
 * 用于 REST API 前后端交互。
 */
@Serializable
data class AgentAnalysisResultDto(
    val id: String,
    val tsCode: String?,
    val analysisType: String?,
    val title: String?,
    val contentMd: String,
    val metadataJson: String?,
    val tradeDate: String?,
    val createdAt: String,
    val updatedAt: String
)

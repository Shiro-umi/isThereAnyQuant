package org.shiroumi.quant_kmp.model

import kotlinx.serialization.Serializable
import model.ws.AgentInterruption
import model.ws.AgentLogEntry

/**
 * 通用聊天消息模型
 *
 * 适用于 UI 展示和数据传输
 */
@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String, // "user" or "assistant"
    val content: String = "",
    val reasoning: String = "",
    val isThinking: Boolean = false,
    val isReasoningExpanded: Boolean = true,
    val logs: List<AgentLogEntry> = emptyList(),
    /** 后端推送的本轮中断信息；非空时前端在该消息下方渲染"继承上下文继续"按钮。 */
    val interruption: AgentInterruption? = null
)

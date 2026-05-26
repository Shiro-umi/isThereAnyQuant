package org.shiroumi.agent.state

import com.agentclientprotocol.model.ToolCallId
import model.ws.AgentInterruption
import model.ws.AgentLogEntry
import model.ws.AgentStatus

/**
 * 工具调用数据类
 */
data class ToolCall(
    val id: ToolCallId,
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * 审批请求数据类
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val description: String
)

/**
 * StateManager 在每一次 ACP 事件被消化时同时 emit 出的细粒度增量。
 *
 * 与 [ClaudeState] 的关系：
 * - [ClaudeState] 是累积视图，给"想读当前完整状态"的人用（订阅瞬间、终态兜底）
 * - [ClaudeUpdate] 是离散事件，给"想转发增量"的人用（ktor 把它翻成 AgentStreamPayload.Delta）
 *
 * 设计要点：所有可累积字段（output / thinking / logs）都用追加语义，
 * 避免下游为了拿"本帧新内容"反复 diff 上一帧的累积值。
 */
sealed class ClaudeUpdate {
    abstract val sessionId: String?

    /** 状态机迁移（不带数据，纯状态变化）。 */
    data class StatusChanged(
        override val sessionId: String?,
        val status: model.ws.AgentStatus
    ) : ClaudeUpdate()

    /** 模型输出新 chunk（追加到 output 与最后一条 OUTPUT log）。 */
    data class OutputAppended(
        override val sessionId: String?,
        val content: String
    ) : ClaudeUpdate()

    /** 模型 thinking 新 chunk（追加到 thinking 与最后一条 THOUGHT log）。 */
    data class ThinkingAppended(
        override val sessionId: String?,
        val content: String
    ) : ClaudeUpdate()

    /** 新增日志条目（工具调用开始等）。 */
    data class LogAppended(
        override val sessionId: String?,
        val entry: model.ws.AgentLogEntry
    ) : ClaudeUpdate()

    /**
     * 已存在日志条目被字段级更新（工具调用 RUNNING→COMPLETED/FAILED、补回 toolInput 等）。
     * `null` 字段表示不更新。
     */
    data class LogPatched(
        override val sessionId: String?,
        val logId: String,
        val toolInput: String? = null,
        val toolOutput: String? = null,
        val toolStatus: model.ws.ToolCallStatus? = null,
        val toolCallId: String? = null
    ) : ClaudeUpdate()

    /** 当前正在执行的工具变更；null = 清空。 */
    data class ActiveToolChanged(
        override val sessionId: String?,
        val toolName: String?
    ) : ClaudeUpdate()

    /** pending approvals 列表整体替换。 */
    data class PendingApprovalsChanged(
        override val sessionId: String?,
        val ids: List<String>,
        val toolNames: List<String>
    ) : ClaudeUpdate()

    /** error 文本设置。 */
    data class ErrorSet(
        override val sessionId: String?,
        val message: String
    ) : ClaudeUpdate()

    /** AgentInterruption 设置（中断/超时）。 */
    data class InterruptionSet(
        override val sessionId: String?,
        val interruption: model.ws.AgentInterruption
    ) : ClaudeUpdate()
}

/**
 * Claude Agent 完整状态
 */
data class ClaudeState(
    val sessionId: String? = null,
    val status: AgentStatus = AgentStatus.IDLE,
    val thinking: String = "",           // 思考内容（累积）
    val output: String = "",             // 输出内容（累积）
    val activeTool: ToolCall? = null,    // 当前正在执行的工具调用
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    val error: String? = null,
    val logs: List<AgentLogEntry> = emptyList(),
    val interruption: AgentInterruption? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun hasPendingApprovals(): Boolean = pendingApprovals.isNotEmpty()
    fun pendingApprovalCount(): Int = pendingApprovals.size
    fun isActive(): Boolean = status != AgentStatus.IDLE && status != AgentStatus.COMPLETED && status != AgentStatus.ERROR
}

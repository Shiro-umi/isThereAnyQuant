package org.shiroumi.quant_kmp.ui.agent.state

import model.ws.AgentInterruption
import model.ws.AgentLogEntry
import model.ws.AgentStatus
import org.shiroumi.quant_kmp.model.ChatMessage
import org.shiroumi.quant_kmp.ui.core.mvi.UiAction
import org.shiroumi.quant_kmp.ui.core.mvi.UiEffect
import org.shiroumi.quant_kmp.ui.core.mvi.UiState

object AgentContract {

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class PendingApproval(
        val requestId: String,
        val toolName: String,
        val description: String
    )

    data class State(
        val sessionId: String? = null,
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val agentStatus: AgentStatus = AgentStatus.IDLE,
        val thinking: String = "",
        val output: String = "",
        val activeToolName: String? = null,
        val pendingApprovals: List<PendingApproval> = emptyList(),
        val messages: List<ChatMessage> = emptyList(),
        val inputText: String = "",
        val isInputEnabled: Boolean = true,
        override val isLoading: Boolean = false,
        override val errorMessage: String? = null,
        val logs: List<AgentLogEntry> = emptyList()
    ) : UiState {
        val isProcessing: Boolean
            get() = agentStatus == AgentStatus.THINKING || agentStatus == AgentStatus.EXECUTING

        val canSendMessage: Boolean
            get() = connectionStatus == ConnectionStatus.CONNECTED &&
                    !isProcessing &&
                    agentStatus != AgentStatus.AWAITING_APPROVAL &&
                    inputText.isNotBlank()
    }

    sealed interface Action : UiAction {
        data object Connect : Action
        data object Disconnect : Action
        data object NewSession : Action
        data class SessionCreated(val sessionId: String) : Action
        data class UpdateInput(val text: String) : Action
        data class SendMessage(val analysisType: String? = null) : Action
        data object StopAgent : Action
        data object ResumeAgent : Action
        data class AgentStateUpdated(
            val sessionId: String,
            val status: AgentStatus,
            val thinking: String,
            val output: String,
            val activeToolName: String?,
            val pendingApprovals: List<PendingApproval>,
            val error: String?,
            val logs: List<AgentLogEntry> = emptyList(),
            val interruption: AgentInterruption? = null
        ) : Action
        data class ApproveTool(val requestId: String) : Action
        data class RejectTool(val requestId: String) : Action
        data class SetError(val message: String) : Action
    }

    sealed interface Effect : UiEffect {
        data class ShowToast(val message: String) : Effect
        data object ScrollToBottom : Effect
    }
}

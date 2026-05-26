package org.shiroumi.quant_kmp.ui.agent.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import model.agent.AgentAnalysisContext
import model.agent.AgentAnalysisType
import model.ws.AgentLogEntry
import model.ws.AgentLogType
import model.ws.AgentStatePayload
import model.ws.AgentStatus
import model.ws.AgentStreamPayload
import model.ws.WsAction
import model.ws.WsTopic
import org.shiroumi.quant_kmp.model.ChatMessage
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.service.StockContextProvider
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract
import org.shiroumi.quant_kmp.ui.core.mvi.MviViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AgentViewModel : ViewModel(), MviViewModel<AgentContract.State, AgentContract.Action, AgentContract.Effect> {

    private val _state = MutableStateFlow(AgentContract.State())
    override val state: StateFlow<AgentContract.State> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<AgentContract.Effect>()
    override val effect: SharedFlow<AgentContract.Effect> = _effect.asSharedFlow()

    private var eventListenJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var currentAssistantMessageId: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 客户端本地聚合的 Agent 累积视图。
     *
     * SYNC 帧整体替换；Delta 帧按字段 apply（output/thinking 追加、logs 增删改、approvals 全量替换）。
     * 重连恢复订阅时服务端会重发 SYNC，自然把这里替换成正确 baseline。
     * key 是 sessionId，避免会话切换时旧 snapshot 污染新会话。
     */
    private val aggregatedSnapshots = mutableMapOf<String, AgentStatePayload>()

    @OptIn(ExperimentalUuidApi::class)
    private fun generateMessageId(): String = Uuid.random().toString()

    init {
        startListeningGlobalEvents()
        // Agent 自动上线：WebSocket 连接后自动创建会话
        connect()
    }

    override fun dispatch(action: AgentContract.Action) {
        when (action) {
            is AgentContract.Action.Connect -> connect()
            is AgentContract.Action.Disconnect -> disconnect()
            is AgentContract.Action.NewSession -> newSession()
            is AgentContract.Action.SessionCreated -> handleSessionCreated(action.sessionId)
            is AgentContract.Action.UpdateInput -> _state.update { it.copy(inputText = action.text) }
            is AgentContract.Action.SendMessage -> sendMessage(action.analysisType)
            is AgentContract.Action.StopAgent -> stopAgent()
            is AgentContract.Action.ResumeAgent -> resumeAgent()
            is AgentContract.Action.AgentStateUpdated -> handleAgentStateUpdated(action)
            is AgentContract.Action.ApproveTool -> approveTool(action.requestId)
            is AgentContract.Action.RejectTool -> rejectTool(action.requestId)
            is AgentContract.Action.SetError -> _state.update {
                it.copy(errorMessage = action.message, connectionStatus = AgentContract.ConnectionStatus.ERROR)
            }
        }
    }

    private fun connect() {
        _state.update { it.copy(connectionStatus = AgentContract.ConnectionStatus.CONNECTING) }

        // 确保全局 WebSocket 已连接
        GlobalWebSocketClient.connect()

        // 发送创建会话命令
        GlobalWebSocketClient.createAgentSession()

        scheduleConnectionTimeout()
    }

    /**
     * 开启一个全新的 Agent 会话：
     * 1) 关闭并取消订阅旧的 ACP 子会话；
     * 2) 清空本地消息、待审批、输入等会话状态；
     * 3) 重新发起 createAgentSession，等待后端 AGENT_SESSION/SYNC 回包接管。
     */
    private fun newSession() {
        val oldSid = _state.value.sessionId
        if (oldSid != null) {
            GlobalWebSocketClient.unsubscribeAgent(oldSid)
            GlobalWebSocketClient.closeAgentSession(oldSid)
        }
        currentAssistantMessageId = null
        _state.update {
            AgentContract.State(connectionStatus = AgentContract.ConnectionStatus.CONNECTING)
        }
        GlobalWebSocketClient.createAgentSession()
        scheduleConnectionTimeout()
    }

    private fun scheduleConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = viewModelScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_state.value.connectionStatus == AgentContract.ConnectionStatus.CONNECTING) {
                dispatch(AgentContract.Action.SetError("连接超时，请检查网络或后端服务"))
            }
        }
    }

    private fun disconnect() {
        val sessionId = _state.value.sessionId ?: return
        GlobalWebSocketClient.unsubscribeAgent(sessionId)
        _state.update {
            it.copy(
                sessionId = null,
                connectionStatus = AgentContract.ConnectionStatus.DISCONNECTED,
                agentStatus = AgentStatus.IDLE
            )
        }
    }

    private fun handleSessionCreated(sessionId: String) {
        _state.update {
            it.copy(
                sessionId = sessionId,
                connectionStatus = AgentContract.ConnectionStatus.CONNECTED,
                agentStatus = AgentStatus.IDLE
            )
        }
        GlobalWebSocketClient.subscribeAgent(sessionId)
    }

    private fun sendMessage(analysisType: String? = null) {
        val currentState = _state.value
        if (!currentState.canSendMessage) return

        val message = currentState.inputText
        val sessionId = currentState.sessionId ?: return

        // === IDE 风格上下文透传 ===
        // 若用户当前在行情页并已选中股票，则在发送给 Agent 的 prompt 中自动注入上下文。
        // UI 仅展示用户的原始消息，注入的上下文对用户透明。
        val contextStock = StockContextProvider.selectedStock.value
        val analysisContext = contextStock?.let {
            AgentAnalysisContext(
                tsCode = it.code,
                stockName = it.name,
                analysisType = analysisType ?: AgentAnalysisType.GENERAL.code
            )
        }
        val promptToSend = if (contextStock != null) {
            "[当前上下文 - 用户正在查看行情]\n" +
                "股票: ${contextStock.name}（${contextStock.code}）\n" +
                "涨跌幅: ${if (contextStock.changePercent >= 0) "+" else ""}${contextStock.changePercent}%\n" +
                "---\n" +
                message
        } else {
            message
        }

        val userMsg = ChatMessage(id = generateMessageId(), role = "user", content = message) // UI 显示原始消息
        val assistantMsg = ChatMessage(id = generateMessageId(), role = "assistant", content = "", isThinking = true)
        currentAssistantMessageId = assistantMsg.id

        _state.update { s ->
            s.copy(
                messages = s.messages + userMsg + assistantMsg,
                inputText = "",
                isInputEnabled = false
            )
        }

        viewModelScope.launch {
            _effect.emit(AgentContract.Effect.ScrollToBottom)
        }

        GlobalWebSocketClient.sendAgentPrompt(sessionId, promptToSend, analysisContext)
    }

    private fun stopAgent() {
        val sessionId = _state.value.sessionId ?: return
        // 立即乐观更新 UI，让输入框恢复可用（后端状态推送到来时会再次更新）
        _state.update { s ->
            val updatedMessages = s.messages.toMutableList()
            val targetIdx = currentAssistantMessageId?.let { id ->
                updatedMessages.indexOfLast { it.id == id }
            } ?: updatedMessages.indexOfLast { it.role == "assistant" }
            if (targetIdx >= 0) {
                val target = updatedMessages[targetIdx]
                if (target.isThinking) {
                    updatedMessages[targetIdx] = target.copy(isThinking = false)
                }
            }
            s.copy(
                isInputEnabled = true,
                pendingApprovals = emptyList(),
                messages = updatedMessages
            )
        }
        currentAssistantMessageId = null
        GlobalWebSocketClient.stopAgentSession(sessionId)
    }

    private fun handleAgentStateUpdated(action: AgentContract.Action.AgentStateUpdated) {
        if (action.sessionId != _state.value.sessionId) return

        _state.update { s ->
            val updatedMessages = s.messages.toMutableList()
            val targetIdx = currentAssistantMessageId?.let { id ->
                updatedMessages.indexOfLast { it.id == id }
            } ?: updatedMessages.indexOfLast { it.role == "assistant" }
            if (targetIdx >= 0) {
                val target = updatedMessages[targetIdx]
                val isCompleted = action.status == AgentStatus.COMPLETED || action.status == AgentStatus.ERROR
                updatedMessages[targetIdx] = target.copy(
                    content = action.output,
                    reasoning = action.thinking,
                    isThinking = !isCompleted,
                    logs = action.logs,
                    interruption = action.interruption
                )
            }

            s.copy(
                agentStatus = action.status,
                thinking = action.thinking,
                output = action.output,
                activeToolName = action.activeToolName,
                pendingApprovals = action.pendingApprovals,
                errorMessage = action.error,
                messages = updatedMessages,
                logs = action.logs,
                isInputEnabled = action.status == AgentStatus.COMPLETED ||
                        action.status == AgentStatus.ERROR ||
                        action.status == AgentStatus.IDLE
            )
        }

        if (action.status == AgentStatus.COMPLETED || action.status == AgentStatus.ERROR) {
            currentAssistantMessageId = null
        }
        // 流式更新不触发 ScrollToBottom，避免打断用户阅读思考内容
    }

    /**
     * 在已被中断的会话上"继承上下文继续"。
     * 不会新建消息气泡：把最后一条 assistant 消息的 interruption 清掉、重新置为思考中，
     * 后续后端推送会以增量形式继续填充正文。
     */
    private fun resumeAgent() {
        val sessionId = _state.value.sessionId ?: return
        _state.update { s ->
            val updatedMessages = s.messages.toMutableList()
            val targetIdx = updatedMessages.indexOfLast { it.role == "assistant" }
            if (targetIdx >= 0) {
                val target = updatedMessages[targetIdx]
                if (target.interruption?.resumable == true) {
                    updatedMessages[targetIdx] = target.copy(
                        interruption = null,
                        isThinking = true
                    )
                    currentAssistantMessageId = target.id
                }
            }
            s.copy(
                messages = updatedMessages,
                isInputEnabled = false
            )
        }
        GlobalWebSocketClient.resumeAgentSession(sessionId)
    }

    private fun approveTool(requestId: String) {
        val sessionId = _state.value.sessionId ?: return
        GlobalWebSocketClient.approveAgentTool(sessionId, requestId)
        _state.update { s ->
            s.copy(pendingApprovals = s.pendingApprovals.filter { it.requestId != requestId })
        }
    }

    private fun rejectTool(requestId: String) {
        val sessionId = _state.value.sessionId ?: return
        GlobalWebSocketClient.rejectAgentTool(sessionId, requestId)
        _state.update { s ->
            s.copy(pendingApprovals = s.pendingApprovals.filter { it.requestId != requestId })
        }
    }

    private fun startListeningGlobalEvents() {
        eventListenJob?.cancel()
        eventListenJob = viewModelScope.launch {
            GlobalWebSocketClient.eventsFlow.collect { event ->
                when (event.topic) {
                    WsTopic.AGENT_SESSION -> {
                        when (event.action) {
                            WsAction.SYNC -> {
                                val sessionId = event.targetId ?: event.payload
                                sessionId?.let { dispatch(AgentContract.Action.SessionCreated(it)) }
                            }
                            WsAction.ERROR -> {
                                val errorMsg = event.payload ?: "Agent 会话创建失败"
                                dispatch(AgentContract.Action.SetError(errorMsg))
                            }
                            else -> {}
                        }
                    }
                    WsTopic.AGENT_STREAM -> {
                        val currentSessionId = _state.value.sessionId
                        val targetId = event.targetId
                        if (targetId != null && targetId == currentSessionId) {
                            event.payload?.let { payload ->
                                try {
                                    val parsed = json.decodeFromString<AgentStreamPayload>(payload)
                                    handleAgentStreamPayload(targetId, parsed)
                                } catch (e: Exception) {
                                    println("[AgentViewModel] Failed to parse agent stream payload: ${e.message}")
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 统一处理 AGENT_STREAM 帧：把 wire 协议聚合成完整的 [AgentStatePayload] 再 dispatch。
     *
     * Snapshot → 整体替换本地累积视图（订阅瞬间 / 重连恢复）
     * Delta → 在本地累积视图上 apply 增量；如果带 snapshot 兜底（终态帧），优先使用兜底快照
     */
    private fun handleAgentStreamPayload(sessionId: String, payload: AgentStreamPayload) {
        val nextSnapshot = when (payload) {
            is AgentStreamPayload.Snapshot -> payload.state
            is AgentStreamPayload.Delta -> {
                // 终态帧自带 snapshot：用它做"最终对账"，丢中间 Delta 也能落到正确终态视图
                val baseline = payload.snapshot
                    ?: aggregatedSnapshots[sessionId]
                    ?: AgentStatePayload(sessionId = sessionId, status = AgentStatus.IDLE)
                applyDelta(baseline, payload)
            }
        }
        aggregatedSnapshots[sessionId] = nextSnapshot

        val approvals = nextSnapshot.pendingApprovalIds.zip(
            nextSnapshot.pendingApprovalTools.padEnd(nextSnapshot.pendingApprovalIds.size)
        ).map { (id, tool) ->
            AgentContract.PendingApproval(
                requestId = id,
                toolName = tool,
                description = "请求执行工具: $tool"
            )
        }
        dispatch(
            AgentContract.Action.AgentStateUpdated(
                sessionId = nextSnapshot.sessionId,
                status = nextSnapshot.status,
                thinking = nextSnapshot.thinking,
                output = nextSnapshot.output,
                activeToolName = nextSnapshot.activeToolName,
                pendingApprovals = approvals,
                error = nextSnapshot.error,
                logs = nextSnapshot.logs,
                interruption = nextSnapshot.interruption
            )
        )
    }

    /**
     * 在基线 snapshot 之上 apply 增量字段，得到新的累积视图。
     *
     * 语义见 [AgentStreamPayload.Delta] 字段说明：
     * - outputAppend / thinkingAppend 是文本追加
     * - newLogs 在尾部追加
     * - logPatches 按 logId 字段级覆盖（null 字段不动）
     * - appendToLastLog 把内容拼到列表中最后一条同 type 条目尾部
     * - activeToolNamePresent=true 时 activeToolName 字段才生效
     * - pendingApprovals 整体替换
     * - status / interruption / error / context 提供时覆盖
     */
    private fun applyDelta(baseline: AgentStatePayload, delta: AgentStreamPayload.Delta): AgentStatePayload {
        var logs = baseline.logs
        delta.appendToLastLog?.let { append ->
            val mutable = logs.toMutableList()
            val last = mutable.lastOrNull()
            if (last != null && last.type == append.type) {
                mutable[mutable.lastIndex] = last.copy(content = last.content + append.content)
            } else {
                mutable.add(AgentLogEntry(type = append.type, content = append.content))
            }
            logs = mutable
        }
        if (delta.newLogs.isNotEmpty()) {
            logs = logs + delta.newLogs
        }
        if (delta.logPatches.isNotEmpty()) {
            val byId = logs.associateBy { it.id }.toMutableMap()
            delta.logPatches.forEach { patch ->
                val original = byId[patch.logId] ?: return@forEach
                byId[patch.logId] = original.copy(
                    toolInput = patch.toolInput ?: original.toolInput,
                    toolOutput = patch.toolOutput ?: original.toolOutput,
                    toolStatus = patch.toolStatus ?: original.toolStatus,
                    toolCallId = patch.toolCallId ?: original.toolCallId
                )
            }
            // 保持原顺序：按 id 重组同样的 list
            logs = logs.map { byId[it.id] ?: it }
        }

        val nextActiveToolName = if (delta.activeToolNamePresent) delta.activeToolName else baseline.activeToolName
        val nextPendingIds = delta.pendingApprovals?.ids ?: baseline.pendingApprovalIds
        val nextPendingTools = delta.pendingApprovals?.toolNames ?: baseline.pendingApprovalTools

        return baseline.copy(
            sessionId = delta.sessionId,
            status = delta.status ?: baseline.status,
            thinking = baseline.thinking + (delta.thinkingAppend ?: ""),
            output = baseline.output + (delta.outputAppend ?: ""),
            activeToolName = nextActiveToolName,
            pendingApprovalIds = nextPendingIds,
            pendingApprovalTools = nextPendingTools,
            error = delta.error ?: baseline.error,
            logs = logs,
            context = delta.context ?: baseline.context,
            interruption = delta.interruption ?: baseline.interruption
        )
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        eventListenJob?.cancel()
        connectionTimeoutJob?.cancel()
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000L
    }
}

private fun List<String>.padEnd(size: Int, default: String = "Unknown"): List<String> =
    if (this.size >= size) this.take(size)
    else this + List(size - this.size) { default }

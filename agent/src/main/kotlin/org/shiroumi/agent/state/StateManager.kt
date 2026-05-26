package org.shiroumi.agent.state

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import model.ws.AgentInterruption
import model.ws.AgentInterruptionReason
import model.ws.AgentLogEntry
import model.ws.AgentLogType
import model.ws.AgentStatus
import model.ws.ToolCallStatus

private val logger = KotlinLogging.logger {}

/**
 * 权限响应结果
 */
sealed class PermissionResult {
    data class Approved(val optionId: String) : PermissionResult()
    data object Rejected : PermissionResult()
    data object Cancelled : PermissionResult()
}

/**
 * 待处理的权限请求
 */
internal data class PendingPermissionRequest(
    val requestId: String,
    val toolCallId: String,
    val toolName: String,
    val description: String,
    val availableOptions: List<String>,
    val completer: CompletableDeferred<PermissionResult>
)

/**
 * 状态管理器
 * 负责将SDK事件流转换为UI状态
 */
class StateManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow(ClaudeState())
    val state: StateFlow<ClaudeState> = _state.asStateFlow()

    /**
     * ACP 事件被消化时同步 emit 的离散增量流。
     *
     * 必须满足两个条件：
     * 1. 容量足够吸收"模型流式输出+多工具调用"瞬时尖刺，避免在 ACP 入口处丢帧
     * 2. 上层（AgentWebSocketService）只能在一个协程里串行 collect，
     *    所以 [extraBufferCapacity] 不需要超过推送侧能消化的量级
     *
     * 这里取 1024：单次报告的 chunk + 工具调用事件总量典型在几百量级，
     * 即便公网链路把 ktor 推送堵住，1024 仍能撑过几次秒级抖动；
     * 真正堵到溢出时由 [BufferOverflow.SUSPEND] 反压 ACP 事件流，
     * 让模型等等（比静默丢帧导致 UI 错乱安全得多）。
     */
    private val _updates = MutableSharedFlow<ClaudeUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )
    val updates: SharedFlow<ClaudeUpdate> = _updates.asSharedFlow()

    private val thinkingBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()
    private var currentSessionId: String? = null
    private val pendingPermissionRequests = mutableMapOf<String, PendingPermissionRequest>()
    private val isInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val skillReadToolCallIds = mutableSetOf<String>()
    private val skillPathPattern = Regex("""\.claude/skills/.+/SKILL\.md""")

    /** 最近一次收到 ACP event 的时间戳（毫秒）；watchdog 用来判定空闲超时。 */
    private val lastEventAt = java.util.concurrent.atomic.AtomicLong(0L)
    val lastEventTimestamp: Long get() = lastEventAt.get()

    /**
     * 发射 ACP 事件链路上的增量。
     *
     * 这些增量承载 output/thinking chunk 与工具状态，是前端流式体验的业务事实；
     * 当下游 socket 发送慢到缓冲耗尽时必须挂起并形成背压，而不是静默丢帧。
     */
    private suspend fun emitUpdate(update: ClaudeUpdate) {
        _updates.emit(update)
    }

    /**
     * 非挂起入口（审批按钮、外部 interrupt、工具命令回填等）无法安全阻塞调用方。
     * 这些路径仍用 best-effort，但必须记录丢帧；终态 Delta 自带 Snapshot 会兜底最终视图。
     */
    private fun emitBestEffort(update: ClaudeUpdate) {
        if (!_updates.tryEmit(update)) {
            logger.warn { "[StateManager] Drop non-streaming update because update buffer is full: ${update::class.simpleName}" }
        }
    }

    /**
     * 由 AcpClient 在 requestPermissions 时回调，将真实命令文本回填到对应的 log 条目
     */
    fun updateToolCallInput(toolCallId: String, actualCommand: String) {
        logger.info { "[StateManager] 🔧 TOOL CMD: id=$toolCallId cmd=${actualCommand.take(300)}" }
        var patchedLogId: String? = null
        var patchedToolCallIdFilled = false
        _state.update { s ->
            val updatedLogs = s.logs.toMutableList()
            // 优先通过 toolCallId 匹配
            val idx = updatedLogs.indexOfLast {
                it.type == AgentLogType.TOOL_CALL && it.toolCallId == toolCallId
            }
            if (idx >= 0) {
                val original = updatedLogs[idx]
                updatedLogs[idx] = original.copy(toolInput = actualCommand)
                patchedLogId = original.id
            } else {
                // 回退逻辑：如果 ID 没匹配上，寻找最后一个输入为空的工具调用
                val fallbackIdx = updatedLogs.indexOfLast {
                    it.type == AgentLogType.TOOL_CALL && it.toolInput.isNullOrEmpty()
                }
                if (fallbackIdx >= 0) {
                    val original = updatedLogs[fallbackIdx]
                    updatedLogs[fallbackIdx] = original.copy(
                        toolInput = actualCommand,
                        toolCallId = toolCallId // 顺便把 ID 补上
                    )
                    patchedLogId = original.id
                    patchedToolCallIdFilled = true
                }
            }
            s.copy(logs = updatedLogs)
        }
        patchedLogId?.let { id ->
            emitBestEffort(
                ClaudeUpdate.LogPatched(
                    sessionId = currentSessionId,
                    logId = id,
                    toolInput = actualCommand,
                    toolCallId = if (patchedToolCallIdFilled) toolCallId else null
                )
            )
        }
    }

    fun processEventFlow(events: Flow<Event>, sessionId: String? = null): Job {
        reset()
        currentSessionId = sessionId
        lastEventAt.set(System.currentTimeMillis())

        _state.update {
            it.copy(
                sessionId = sessionId,
                status = AgentStatus.THINKING,
                timestamp = System.currentTimeMillis()
            )
        }
        emitBestEffort(ClaudeUpdate.StatusChanged(sessionId, AgentStatus.THINKING))

        logger.info { "[StateManager] Started processing event flow for session: $sessionId" }

        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)

        return events
            .onStart {
                logger.info { "[StateManager] Flow collection started - waiting for events..." }
            }
            .onEach { event ->
                lastEventAt.set(System.currentTimeMillis())
                if (isInterrupted.get()) {
                    logger.debug { "[StateManager] Ignoring event because flow is interrupted" }
                    return@onEach
                }
                val count = eventCount.incrementAndGet()
                logger.info { "[StateManager] ◆ event=${event::class.simpleName} count=$count" }
                when (event) {
                    is Event.SessionUpdateEvent -> handleSessionUpdate(event.update)
                    is Event.PromptResponseEvent -> handlePromptResponse(event.response)
                }
            }
            .catch { e ->
                logger.error(e) { "[StateManager] Error processing event flow: ${e.message}" }
                setProcessError("事件流异常：${e.message ?: e::class.simpleName}")
            }
            .onCompletion { cause ->
                if (cause == null) {
                    logger.info { "[StateManager] Event flow completed normally" }
                } else {
                    logger.error(cause) { "[StateManager] Event flow completed with error: ${cause.message}" }
                }
            }
            .launchIn(scope)
    }

    suspend fun requestUserPermission(
        requestId: String,
        toolCallId: String,
        toolName: String,
        description: String,
        availableOptions: List<String>,
        timeoutMs: Long = 300000L
    ): PermissionResult {
        logger.info { "[StateManager] Requesting user permission: $requestId for tool: $toolName" }

        val completer = CompletableDeferred<PermissionResult>()
        val pendingRequest = PendingPermissionRequest(
            requestId = requestId,
            toolCallId = toolCallId,
            toolName = toolName,
            description = description,
            availableOptions = availableOptions,
            completer = completer
        )

        pendingPermissionRequests[requestId] = pendingRequest

        val approvalRequest = ApprovalRequest(
            id = requestId,
            toolName = toolName,
            description = description
        )

        var afterAddPendingIds: List<String> = emptyList()
        var afterAddPendingTools: List<String> = emptyList()
        var statusBecameAwaiting = false
        _state.update { currentState ->
            if (currentState.status != AgentStatus.AWAITING_APPROVAL) statusBecameAwaiting = true
            val newApprovals = currentState.pendingApprovals + approvalRequest
            afterAddPendingIds = newApprovals.map { it.id }
            afterAddPendingTools = newApprovals.map { it.toolName }
            currentState.copy(
                status = AgentStatus.AWAITING_APPROVAL,
                pendingApprovals = newApprovals,
                timestamp = System.currentTimeMillis()
            )
        }
        if (statusBecameAwaiting) {
            emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.AWAITING_APPROVAL))
        }
        emitUpdate(
            ClaudeUpdate.PendingApprovalsChanged(
                sessionId = currentSessionId,
                ids = afterAddPendingIds,
                toolNames = afterAddPendingTools
            )
        )

        val result = withTimeoutOrNull(timeoutMs) {
            completer.await()
        } ?: PermissionResult.Cancelled

        pendingPermissionRequests.remove(requestId)

        if (result is PermissionResult.Cancelled) {
            var statusBecameExecuting = false
            var newPendingIds: List<String> = emptyList()
            var newPendingTools: List<String> = emptyList()
            _state.update { currentState ->
                val updatedApprovals = currentState.pendingApprovals.filter { it.id != requestId }
                val nextStatus = if (updatedApprovals.isEmpty() && currentState.status == AgentStatus.AWAITING_APPROVAL) {
                    AgentStatus.EXECUTING
                } else {
                    currentState.status
                }
                if (nextStatus != currentState.status && nextStatus == AgentStatus.EXECUTING) statusBecameExecuting = true
                newPendingIds = updatedApprovals.map { it.id }
                newPendingTools = updatedApprovals.map { it.toolName }
                currentState.copy(
                    pendingApprovals = updatedApprovals,
                    status = nextStatus,
                    timestamp = System.currentTimeMillis()
                )
            }
            emitUpdate(
                ClaudeUpdate.PendingApprovalsChanged(
                    sessionId = currentSessionId,
                    ids = newPendingIds,
                    toolNames = newPendingTools
                )
            )
            if (statusBecameExecuting) {
                emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.EXECUTING))
            }
        }

        return result
    }

    private suspend fun handleSessionUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentThoughtChunk -> {
                val content = extractTextContent(update.content)
                if (content.isEmpty()) return
                thinkingBuffer.append(content)
                var statusBecameThinking = false
                _state.update { s ->
                    val updatedLogs = s.logs.toMutableList()
                    val last = updatedLogs.lastOrNull()
                    if (last != null && last.type == AgentLogType.THOUGHT) {
                        updatedLogs[updatedLogs.size - 1] = last.copy(content = last.content + content)
                    } else {
                        updatedLogs.add(AgentLogEntry(type = AgentLogType.THOUGHT, content = content))
                    }
                    val previousStatus = s.status
                    if (previousStatus != AgentStatus.THINKING) statusBecameThinking = true
                    s.copy(
                        status = AgentStatus.THINKING,
                        thinking = thinkingBuffer.toString(),
                        logs = updatedLogs,
                        timestamp = System.currentTimeMillis()
                    )
                }
                if (statusBecameThinking) {
                    emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.THINKING))
                }
                emitUpdate(ClaudeUpdate.ThinkingAppended(currentSessionId, content))
            }

            is SessionUpdate.AgentMessageChunk -> {
                val content = extractTextContent(update.content)
                if (content.isEmpty()) return
                outputBuffer.append(content)
                var newStatus: AgentStatus? = null
                _state.update { s ->
                    val updatedLogs = s.logs.toMutableList()
                    val last = updatedLogs.lastOrNull()
                    if (last != null && last.type == AgentLogType.OUTPUT) {
                        updatedLogs[updatedLogs.size - 1] = last.copy(content = last.content + content)
                    } else {
                        updatedLogs.add(AgentLogEntry(type = AgentLogType.OUTPUT, content = content))
                    }
                    val nextStatus =
                        if (s.status == AgentStatus.THINKING) AgentStatus.EXECUTING else s.status
                    if (nextStatus != s.status) newStatus = nextStatus
                    s.copy(
                        status = nextStatus,
                        output = outputBuffer.toString(),
                        logs = updatedLogs,
                        timestamp = System.currentTimeMillis()
                    )
                }
                newStatus?.let { emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, it)) }
                emitUpdate(ClaudeUpdate.OutputAppended(currentSessionId, content))
            }

            is SessionUpdate.ToolCall -> {
                val arguments = extractToolArguments(update)
                val toolCall = ToolCall(
                    id = update.toolCallId,
                    name = update.title,
                    arguments = arguments
                )

                val rawInput = update.rawInput?.toString() ?: ""
                if (skillPathPattern.containsMatchIn(rawInput)) {
                    skillReadToolCallIds.add(update.toolCallId.value)
                }

                val contentSummary = extractContentSummary(update)
                logger.info { "[StateManager] 🔧 TOOL CALL: ${toolCall.name} id=${update.toolCallId} cmd=$contentSummary rawInput=${update.rawInput}" }

                val newEntry = AgentLogEntry(
                    type = AgentLogType.TOOL_CALL,
                    toolName = update.title,
                    toolInput = arguments.toString(),
                    toolStatus = ToolCallStatus.RUNNING,
                    toolCallId = update.toolCallId.value
                )
                var statusBecameExecuting = false
                _state.update { s ->
                    val updatedLogs = s.logs.toMutableList()
                    updatedLogs.add(newEntry)
                    if (s.status != AgentStatus.EXECUTING) statusBecameExecuting = true
                    s.copy(
                        status = AgentStatus.EXECUTING,
                        activeTool = toolCall,
                        logs = updatedLogs,
                        timestamp = System.currentTimeMillis()
                    )
                }
                if (statusBecameExecuting) {
                    emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.EXECUTING))
                }
                emitUpdate(ClaudeUpdate.LogAppended(currentSessionId, newEntry))
                emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, toolCall.name))
            }

            is SessionUpdate.ToolCallUpdate -> {
                val status = update.status
                val output = update.rawOutput
                val isTerminal = status?.name == "COMPLETED" || status?.name == "FAILED"
                if (isTerminal) {
                    logger.info { "[StateManager] 🔧 TOOL UPDATE: ${update.toolCallId} status=$status output=$output" }
                } else {
                    logger.debug { "[StateManager] 🔧 TOOL UPDATE: ${update.toolCallId} status=$status" }
                }
                if (isTerminal) {
                    var patchedLogId: String? = null
                    var patchedStatus: ToolCallStatus? = null
                    var patchedOutput: String? = null
                    _state.update { s ->
                        val updatedLogs = s.logs.toMutableList()
                        // 优先通过 toolCallId 查找，如果没找到再通过名称辅助定位
                        val lastIdx = updatedLogs.indexOfLast {
                            it.type == AgentLogType.TOOL_CALL && (it.toolCallId == update.toolCallId.value || (it.toolCallId == null && it.toolName == s.activeTool?.name))
                        }
                        if (lastIdx >= 0) {
                            val wsStatus = when (status.name) {
                                "COMPLETED" -> ToolCallStatus.COMPLETED
                                "FAILED" -> ToolCallStatus.FAILED
                                else -> ToolCallStatus.RUNNING
                            }
                            val isSkillRead = update.toolCallId.value in skillReadToolCallIds
                            val resolvedOutput = if (isSkillRead) "[Skill 已加载]" else output?.toString()
                            val original = updatedLogs[lastIdx]
                            updatedLogs[lastIdx] = original.copy(
                                toolStatus = wsStatus,
                                toolOutput = resolvedOutput
                            )
                            patchedLogId = original.id
                            patchedStatus = wsStatus
                            patchedOutput = resolvedOutput
                        }
                        s.copy(
                            activeTool = null,
                            logs = updatedLogs,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    patchedLogId?.let { id ->
                        emitUpdate(
                            ClaudeUpdate.LogPatched(
                                sessionId = currentSessionId,
                                logId = id,
                                toolStatus = patchedStatus,
                                toolOutput = patchedOutput
                            )
                        )
                    }
                    emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, null))
                }
            }

            is SessionUpdate.PlanUpdate ->
                logger.info { "[StateManager] 📋 plan entries=${update.entries.size} titles=${update.entries.map { it.content }.take(3)}" }

            is SessionUpdate.AvailableCommandsUpdate ->
                logger.info { "[StateManager] 🗂 availableCommands count=${update.availableCommands.size}" }

            is SessionUpdate.CurrentModeUpdate ->
                logger.info { "[StateManager] ⚙ modeUpdate modeId=${update.currentModeId}" }

            is SessionUpdate.ConfigOptionUpdate ->
                logger.info { "[StateManager] ⚙ configOptionUpdate" }

            is SessionUpdate.SessionInfoUpdate ->
                logger.info { "[StateManager] ℹ sessionInfo title=${update.title}" }

            is SessionUpdate.UsageUpdate ->
                logger.info { "[StateManager] 📊 usage used=${update.used} size=${update.size}" }

            is SessionUpdate.UserMessageChunk ->
                logger.info { "[StateManager] 👤 userMessageChunk" }

            is SessionUpdate.UnknownSessionUpdate ->
                logger.warn { "[StateManager] ⚠ unknownUpdate type=${update.sessionUpdateType}" }
        }
    }

    private suspend fun handlePromptResponse(response: PromptResponse) {
        if (isInterrupted.get()) {
            logger.info { "[StateManager] Ignoring prompt response because flow is interrupted" }
            return
        }
        logger.info { "[StateManager] ✅ promptResponse stopReason=${response.stopReason} thinkingLen=${thinkingBuffer.length} outputLen=${outputBuffer.length}" }

        // stopReason 映射：
        // - END_TURN：模型自然结束 → COMPLETED，无中断
        // - MAX_TOKENS / MAX_TURN_REQUESTS：报告未必完整 → ERROR + 可继续
        // - REFUSAL：模型拒答 → ERROR + 不可继续
        // - CANCELLED：用户主动 stop → ERROR + 可继续
        val (finalStatus, interruption) = when (response.stopReason) {
            StopReason.END_TURN -> AgentStatus.COMPLETED to null
            StopReason.MAX_TOKENS,
            StopReason.MAX_TURN_REQUESTS -> AgentStatus.ERROR to AgentInterruption(
                reason = AgentInterruptionReason.MAX_TURN_REQUESTS,
                message = "模型已达到本轮工具调用或 Token 上限，报告可能未完成。可点击下方按钮继承上下文继续生成。",
                resumable = true
            )
            StopReason.REFUSAL -> AgentStatus.ERROR to AgentInterruption(
                reason = AgentInterruptionReason.REFUSAL,
                message = "模型拒绝继续本轮分析。",
                resumable = false
            )
            StopReason.CANCELLED -> AgentStatus.ERROR to AgentInterruption(
                reason = AgentInterruptionReason.USER_CANCELLED,
                message = "本轮分析已被中断。可点击下方按钮继承上下文继续生成。",
                resumable = true
            )
        }

        var statusChanged = false
        var interruptionSet = false
        var clearedActiveTool = false
        _state.update { current ->
            if (isInterrupted.get()) {
                logger.info { "[StateManager] Drop stale promptResponse: interrupted mid-update" }
                return@update current
            }
            if (current.status != finalStatus) statusChanged = true
            if (current.interruption == null && interruption != null) interruptionSet = true
            if (current.activeTool != null) clearedActiveTool = true
            current.copy(
                status = finalStatus,
                activeTool = null,
                interruption = interruption,
                timestamp = System.currentTimeMillis()
            )
        }
        if (clearedActiveTool) emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, null))
        if (statusChanged) emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, finalStatus))
        if (interruptionSet && interruption != null) {
            emitUpdate(ClaudeUpdate.InterruptionSet(currentSessionId, interruption))
        }
    }

    private fun extractTextContent(content: com.agentclientprotocol.model.ContentBlock): String =
        when (content) {
            is com.agentclientprotocol.model.ContentBlock.Text -> content.text
            else -> ""
        }

    private fun extractToolArguments(update: SessionUpdate.ToolCall): Map<String, Any?> {
        return try {
            val input = update.rawInput
            when {
                // rawInput 有内容时优先用它
                input is kotlinx.serialization.json.JsonObject && input.isNotEmpty() -> {
                    input.mapValues { (_, value) ->
                        when (value) {
                            is kotlinx.serialization.json.JsonPrimitive -> {
                                if (value.isString) value.content else value.toString()
                            }
                            else -> value.toString()
                        }
                    }
                }
                // Terminal 工具的命令在 content blocks 里
                update.content.isNotEmpty() -> {
                    mapOf("cmd" to extractContentSummary(update))
                }
                else -> emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 从 ToolCall.content 列表中提取可读的命令/内容摘要 */
    private fun extractContentSummary(update: SessionUpdate.ToolCall): String {
        return try {
            update.content.joinToString(" | ") { block ->
                when (block) {
                    is com.agentclientprotocol.model.ToolCallContent.Content -> {
                        extractTextContent(block.content).take(300)
                    }
                    is com.agentclientprotocol.model.ToolCallContent.Terminal -> {
                        "terminal:${block.terminalId}"
                    }
                    else -> block.toString().take(100)
                }
            }.ifEmpty { "(no content)" }
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    fun reset() {
        logger.info { "[StateManager] Resetting state" }
        isInterrupted.set(false)
        pendingPermissionRequests.forEach { (_, request) ->
            request.completer.complete(PermissionResult.Cancelled)
        }
        pendingPermissionRequests.clear()
        skillReadToolCallIds.clear()
        thinkingBuffer.clear()
        outputBuffer.clear()
        currentSessionId = null
        lastEventAt.set(0L)
        _state.value = ClaudeState()
    }

    /**
     * 释放资源
     */
    fun dispose() {
        logger.info { "[StateManager] Disposing" }
        reset()
        scope.cancel()
    }

    /**
     * 批准请求
     * 通知等待的协程并更新状态
     */
    suspend fun approve(requestId: String, selectedOptionId: String? = null) {
        logger.info { "[StateManager] Approving request: $requestId" }

        val pendingRequest = pendingPermissionRequests[requestId]
        pendingRequest?.let { request ->
            val optionId = selectedOptionId ?: request.availableOptions.firstOrNull()
            if (optionId != null) {
                request.completer.complete(PermissionResult.Approved(optionId))
            } else {
                request.completer.complete(PermissionResult.Rejected)
            }
        }

        applyApprovalRemoval(requestId)
    }

    suspend fun reject(requestId: String) {
        logger.info { "[StateManager] Rejecting request: $requestId" }

        pendingPermissionRequests[requestId]?.completer?.complete(PermissionResult.Rejected)

        applyApprovalRemoval(requestId)
    }

    private suspend fun applyApprovalRemoval(requestId: String) {
        var newPendingIds: List<String> = emptyList()
        var newPendingTools: List<String> = emptyList()
        var newStatus: AgentStatus? = null
        _state.update { currentState ->
            val updatedApprovals = currentState.pendingApprovals.filter { it.id != requestId }
            val nextStatus = if (updatedApprovals.isEmpty()) AgentStatus.EXECUTING else AgentStatus.AWAITING_APPROVAL
            if (nextStatus != currentState.status) newStatus = nextStatus
            newPendingIds = updatedApprovals.map { it.id }
            newPendingTools = updatedApprovals.map { it.toolName }
            currentState.copy(
                pendingApprovals = updatedApprovals,
                status = nextStatus,
                timestamp = System.currentTimeMillis()
            )
        }
        emitUpdate(
            ClaudeUpdate.PendingApprovalsChanged(
                sessionId = currentSessionId,
                ids = newPendingIds,
                toolNames = newPendingTools
            )
        )
        newStatus?.let { emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, it)) }
    }

    suspend fun setError(errorMessage: String) {
        logger.error { "[StateManager] Setting error: $errorMessage" }
        var statusChanged = false
        _state.update {
            if (it.status != AgentStatus.ERROR) statusChanged = true
            it.copy(
                status = AgentStatus.ERROR,
                error = errorMessage,
                timestamp = System.currentTimeMillis()
            )
        }
        if (statusChanged) emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.ERROR))
        emitUpdate(ClaudeUpdate.ErrorSet(currentSessionId, errorMessage))
    }

    /**
     * 标记本轮事件流因为长时间无活动而被空闲超时打断。
     * 由 [AgentBridgeImpl] 的 watchdog 在触发 interrupt 之后调用，
     * 把 [AgentInterruption] 写入状态供前端渲染"继承上下文继续"按钮。
     */
    suspend fun markIdleTimeout(idleMs: Long) {
        logger.warn { "[StateManager] Idle timeout reached after ${idleMs}ms; marking session as resumable error" }
        val interruption = AgentInterruption(
            reason = AgentInterruptionReason.IDLE_TIMEOUT,
            message = "Agent 已经 ${idleMs / 1000} 秒没有产生新的输出，可能是网络抖动或模型卡住。可以继承上下文继续生成。",
            resumable = true
        )
        val errorMessage = "事件流空闲超过 ${idleMs / 1000} 秒"
        var statusChanged = false
        var clearedActiveTool = false
        _state.update {
            if (it.status != AgentStatus.ERROR) statusChanged = true
            if (it.activeTool != null) clearedActiveTool = true
            it.copy(
                status = AgentStatus.ERROR,
                activeTool = null,
                error = errorMessage,
                interruption = interruption,
                timestamp = System.currentTimeMillis()
            )
        }
        if (clearedActiveTool) emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, null))
        if (statusChanged) emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.ERROR))
        emitUpdate(ClaudeUpdate.ErrorSet(currentSessionId, errorMessage))
        emitUpdate(ClaudeUpdate.InterruptionSet(currentSessionId, interruption))
    }

    /**
     * 标记 ACP 通信或事件流出现进程级异常，前端默认可继续（除非调用方显式标记不可恢复）。
     */
    suspend fun setProcessError(message: String, resumable: Boolean = true) {
        logger.error { "[StateManager] Setting process error: $message" }
        val interruption = AgentInterruption(
            reason = AgentInterruptionReason.PROCESS_ERROR,
            message = "Agent 进程通信异常：$message",
            resumable = resumable
        )
        var statusChanged = false
        var clearedActiveTool = false
        _state.update {
            if (it.status != AgentStatus.ERROR) statusChanged = true
            if (it.activeTool != null) clearedActiveTool = true
            it.copy(
                status = AgentStatus.ERROR,
                activeTool = null,
                error = message,
                interruption = interruption,
                timestamp = System.currentTimeMillis()
            )
        }
        if (clearedActiveTool) emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, null))
        if (statusChanged) emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.ERROR))
        emitUpdate(ClaudeUpdate.ErrorSet(currentSessionId, message))
        emitUpdate(ClaudeUpdate.InterruptionSet(currentSessionId, interruption))
    }

    /**
     * 主动中断当前任务。
     * - 取消所有待审批的权限请求（让 ACP 快速解除阻塞）
     * - 将状态切换为 ERROR + USER_CANCELLED 中断信息，前端据此渲染"继承上下文继续"按钮
     *
     * @param reason 中断原因；watchdog 触发空闲超时时传入 IDLE_TIMEOUT，其它情况默认是用户主动中断
     */
    suspend fun interrupt(reason: AgentInterruptionReason = AgentInterruptionReason.USER_CANCELLED) {
        logger.info { "[StateManager] Interrupting current task reason=$reason" }
        isInterrupted.set(true)
        // 取消所有挂起的权限请求，让 requestPermissions 挂起点快速返回
        pendingPermissionRequests.forEach { (_, request) ->
            request.completer.complete(PermissionResult.Cancelled)
        }
        pendingPermissionRequests.clear()
        val interruption = when (reason) {
            AgentInterruptionReason.IDLE_TIMEOUT -> AgentInterruption(
                reason = reason,
                message = "Agent 长时间没有产生新的输出，已自动中断。可继承上下文继续生成。",
                resumable = true
            )
            AgentInterruptionReason.USER_CANCELLED -> AgentInterruption(
                reason = reason,
                message = "本轮分析已被中断。可继承上下文继续生成。",
                resumable = true
            )
            else -> AgentInterruption(reason = reason, message = "本轮分析已被中断。", resumable = true)
        }
        var statusChanged = false
        var clearedActiveTool = false
        var hadPendingApprovals = false
        _state.update {
            if (it.status != AgentStatus.ERROR) statusChanged = true
            if (it.activeTool != null) clearedActiveTool = true
            if (it.pendingApprovals.isNotEmpty()) hadPendingApprovals = true
            it.copy(
                status = AgentStatus.ERROR,
                activeTool = null,
                pendingApprovals = emptyList(),
                interruption = interruption,
                timestamp = System.currentTimeMillis()
            )
        }
        if (clearedActiveTool) emitUpdate(ClaudeUpdate.ActiveToolChanged(currentSessionId, null))
        if (hadPendingApprovals) {
            emitUpdate(ClaudeUpdate.PendingApprovalsChanged(currentSessionId, emptyList(), emptyList()))
        }
        if (statusChanged) emitUpdate(ClaudeUpdate.StatusChanged(currentSessionId, AgentStatus.ERROR))
        emitUpdate(ClaudeUpdate.InterruptionSet(currentSessionId, interruption))
        logger.info { "[StateManager] Task interrupted, status set to ERROR with reason=$reason" }
    }

    fun getCurrentState(): ClaudeState = _state.value
}

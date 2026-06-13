package org.shiroumi.quant_kmp.service

import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import model.agent.AgentAnalysisContext
import model.agent.AgentPromptRequest
import model.candle.CandlePeriod
import model.ws.*
import org.shiroumi.quant_kmp.createPlatformHttpClient
import org.shiroumi.quant_kmp.util.TokenManager
import org.shiroumi.quant_kmp.util.TokenRefreshHandler
import org.shiroumi.config.AppConfig
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch


/**
 * 仅在 testMode 开启时打印调试日志。
 *
 * 用 inline + lambda 确保 release 模式下不仅跳过 println，
 * 连字符串拼接也不会发生。
 */
private inline fun debugLog(message: () -> String) {
    if (AppConfig.testMode) println(message())
}

/**
 * 全局 WebSocket 客户端单例
 * 
 * 核心职责：
 * 1. 维护与后端的单一长连接 (`/ws/app-stream`)。
 * 2. 接收服务器下发的 `WsEvent` 并分发到全局 `eventsFlow`。
 * 3. 提供发送订阅指令 (`WsCommand`) 的统一接口。
 * 
 * 优势：
 * - 替代分散的独立 WebSocket，极大降低移动端并发连接数，节省电量和网络资源。
 * - 集中处理网络重连和断开逻辑。
 * - ViewModel 只需收集本地内存中的 Flow 数据，实现网络层与视图层的彻底解耦。
 */
object GlobalWebSocketClient {
    /**
     * K 线订阅流事件。
     *
     * `CANDLE_DATA` 现在既可能返回成功快照，也可能返回错误载荷。
     * 前端必须把这两类事件显式区分开，不能再把同一 topic 视为“只会成功”的数据流。
     *
     * Data 分支故意只带 raw payload JSON：
     * 500 根 K 线的反序列化是几十毫秒的主线程开销；
     * 让消费端在 collectLatest body 内解码，旧订阅会因 cancel 直接被丢弃，
     * 避免快速切股时把每只中间股票的 payload 都付一遍解码代价。
     */
    sealed interface CandleStreamEvent {
        data class Data(val payloadJson: String) : CandleStreamEvent {
            fun decode(json: Json): CandleDataPayload =
                json.decodeFromString(payloadJson)
        }
        data class Error(val payload: CandleErrorPayload) : CandleStreamEvent
    }

    private data class CandleStreamEnvelope(
        val targetId: String?,
        val action: WsAction,
        val event: CandleStreamEvent
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile
    private var webSocketSession: DefaultClientWebSocketSession? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var connectJob: Job? = null
    @Volatile
    private var isManualDisconnect = false

    /**
     * 全局连接状态流。
     *
     * 设计目标：让 ViewModel / UI 能感知离线/重连，从而在 isRestorableStateCommand 把命令静默丢弃时
     * 仍能给用户合理的反馈（比如"网络重连中…"取代普通 loading 骨架）。
     *
     * 状态切换点：
     * - connect() 进入连接尝试 → CONNECTING
     * - webSocket 块内握手成功 → CONNECTED
     * - 非主动断开、即将退避重连 → RECONNECTING
     * - disconnect() → DISCONNECTED
     */
    private val _connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()

    // 指数退避重连参数
    private var reconnectDelay = 1000L
    private val maxReconnectDelay = 30000L

    // 当前有效 websocket 主链路的订阅状态，用于断线重连后恢复。
    // 这里不再记录任何旧 realtime topic 或旧盘中链路状态。
    private var activeAgentSessionId: String? = null
    private val activeCandleSubscriptions = mutableMapOf<String, CandleSubscribeRequest>()
    private var activeStockListContext: List<String> = emptyList()
    private val intradaySnapshotOwners = mutableSetOf<String>()
    private val strategyPositionsOwners = mutableSetOf<String>()
    private val strategyPositionTrackingOwners = mutableSetOf<String>()

    private val httpClient = createPlatformHttpClient()
    private val wsBaseUrl = org.shiroumi.config.AppConfig.wsBaseUrl

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 全局控制指令发送通道
     * 保证在 WebSocket 未连接好时缓冲指令，连接成功后立即发送
     */
    private val commandChannel = Channel<WsCommand>(256)
    @OptIn(ExperimentalAtomicApi::class)
    private val commandSeqGenerator = AtomicLong(0L)

    /**
     * 全局事件广播流 (Hot Flow)
     * 所有的 ViewModel 或 Service 都可以通过 collect 此流来监听关心的业务事件。
     * 该流不能反压 WebSocket reader；关键业务状态由 typed StateFlow 兜底。
     */
    private val _eventsFlow = MutableSharedFlow<WsEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventsFlow: SharedFlow<WsEvent> = _eventsFlow.asSharedFlow()
    private val _candleEventsFlow = MutableSharedFlow<CandleStreamEnvelope>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 盘中快照数据状态流
     * 用于实时展示市场情绪、盘中选股候选和股票因子。
     * 策略选股列表统一消费 STRATEGY_POSITIONS.nextSessionSelections。
     */
    private val _intradaySnapshotFlow = MutableStateFlow<IntradaySnapshotPayload?>(null)
    val intradaySnapshotFlow: StateFlow<IntradaySnapshotPayload?> = _intradaySnapshotFlow.asStateFlow()
    private val _intradaySnapshotErrorFlow = MutableStateFlow<String?>(null)
    val intradaySnapshotErrorFlow: StateFlow<String?> = _intradaySnapshotErrorFlow.asStateFlow()

    private val _strategyPositionsFlow = MutableStateFlow<StrategyPositionSnapshot?>(null)
    val strategyPositionsFlow: StateFlow<StrategyPositionSnapshot?> = _strategyPositionsFlow.asStateFlow()

    private val _strategyPositionTrackingFlow = MutableStateFlow<model.candle.StrategyPositionTrackingResponse?>(null)
    val strategyPositionTrackingFlow: StateFlow<model.candle.StrategyPositionTrackingResponse?> = _strategyPositionTrackingFlow.asStateFlow()

    /**
     * 建立全局 WebSocket 连接
     * 该方法具有幂等性，App 生命周期框架可在回到前台时多次调用而不会创建重复连接。
     */
    fun connect() {
        if (isConnected || connectJob?.isActive == true) {
            println("[GlobalWebSocket] Connection already established or connecting, skip connect request.")
            return
        }

        isManualDisconnect = false
        _connectionStateFlow.value = ConnectionState.CONNECTING
        connectJob = scope.launch {
            while (!isManualDisconnect) {
                try {
                    val token = resolveAccessToken()
                    if (token == null) {
                        println("[GlobalWebSocket] Access token unavailable, stopping reconnection loop.")
                        _connectionStateFlow.value = ConnectionState.DISCONNECTED
                        isManualDisconnect = true
                        break
                    }
                    val wsUrl = "$wsBaseUrl/ws/app-stream?token=$token"
                    println("[GlobalWebSocket] Initiating connection to multiplexing channel: $wsUrl...")

                    httpClient.webSocket(urlString = wsUrl) {
                        webSocketSession = this
                        isConnected = true
                        _connectionStateFlow.value = ConnectionState.CONNECTED
                        reconnectDelay = 1000L // 连接成功后重置重试间隔
                        println("[GlobalWebSocket] Successfully connected to multiplexing channel.")

                        // 恢复之前的订阅状态
                        restoreSubscriptions()

                        val sendJob = launch {
                            for (command in commandChannel) {
                                try {
                                    val message = json.encodeToString(command)
                                    send(message)
                                    traceCandleCommand("COMMAND_SENT", command)
                                    debugLog { "[GlobalWebSocket] Dispatching command to server: ${command.command} (Target: ${command.targetId})" }
                                } catch (e: Exception) {
                                    println("[GlobalWebSocket] Failed to dispatch command ${command.command}: ${e.message}")
                                }
                            }
                        }

                        // 持续挂起并消费帧数据
                        try {
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    try {
                                        val event = json.decodeFromString<WsEvent>(text)
                                        handleGlobalEvent(event)
                                        _eventsFlow.tryEmit(event)
                                    } catch (e: Exception) {
                                        println("[GlobalWebSocket] Serialization error for payload: $text, Error: ${e.message}")
                                    }
                                }
                            }
                        } finally {
                            sendJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    println("[GlobalWebSocket] Connection error: ${e.message}")
                } finally {
                    isConnected = false
                    webSocketSession = null
                    println("[GlobalWebSocket] Connection closed.")

                    // 状态切换收敛到 finally 单点：主动断开 → DISCONNECTED；
                    // 非主动断开 → RECONNECTING，并进入指数退避后重试。
                    if (isManualDisconnect) {
                        _connectionStateFlow.value = ConnectionState.DISCONNECTED
                        println("[GlobalWebSocket] Manual disconnect, stopping reconnection loop.")
                    } else {
                        _connectionStateFlow.value = ConnectionState.RECONNECTING
                        println("[GlobalWebSocket] Connection lost unexpectedly, retrying in ${reconnectDelay / 1000}s...")
                        delay(reconnectDelay)
                        reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
                    }
                }
            }
        }
    }

    private suspend fun resolveAccessToken(): String? {
        TokenManager.getAccessToken()?.let { return it }
        println("[GlobalWebSocket] Access token unavailable, attempting refresh before WebSocket connect.")
        return TokenRefreshHandler.refresh(force = true).getOrNull()
    }

    /**
     * 重连成功后自动恢复订阅
     */
    private fun restoreSubscriptions() {
        // 恢复 Agent 会话订阅
        activeAgentSessionId?.let { sessionId ->
            println("[GlobalWebSocket] Restoring agent session subscription: $sessionId")
            subscribeAgent(sessionId)
        }
        // 恢复 K 线主快照订阅
        activeCandleSubscriptions.forEach { (_, request) ->
            println("[GlobalWebSocket] Restoring candle subscription: ${request.tsCode}:${request.period.name}")
            sendCommand(WsCommand(
                CommandType.SUBSCRIBE_CANDLE,
                targetId = request.tsCode,
                payload = json.encodeToString(request)
            ))
        }
        // 恢复股票列表页面上下文。
        // 这是当前 `STOCK_LIST_UPDATE` 唯一合法的上下文来源。
        if (activeStockListContext.isNotEmpty()) {
            println("[GlobalWebSocket] Restoring stock list context: ${activeStockListContext.size} stocks")
            setStockListContext(activeStockListContext)
        }
        // 恢复盘中快照订阅
        if (intradaySnapshotOwners.isNotEmpty()) {
            println("[GlobalWebSocket] Restoring intraday snapshot subscription")
            sendCommand(WsCommand(
                CommandType.SUBSCRIBE,
                targetId = WsTopic.INTRADAY_SNAPSHOT.name
            ))
        }
        if (strategyPositionsOwners.isNotEmpty()) {
            println("[GlobalWebSocket] Restoring strategy positions subscription")
            sendCommand(WsCommand(
                CommandType.SUBSCRIBE,
                targetId = WsTopic.STRATEGY_POSITIONS.name
            ))
        }
        // 恢复策略持仓跟踪订阅
        if (strategyPositionTrackingOwners.isNotEmpty()) {
            println("[GlobalWebSocket] Restoring strategy position tracking subscription")
            sendCommand(WsCommand(
                CommandType.SUBSCRIBE,
                targetId = WsTopic.STRATEGY_POSITION_TRACKING.name
            ))
        }
    }

    /**
     * 处理全局事件，用于状态追踪和最小必要的客户端状态恢复。
     *
     * 当前有效的业务 topic 只有：
     * - `CANDLE_DATA`
     * - `INTRADAY_SNAPSHOT`
     * - `STOCK_LIST_UPDATE`
     * - `MARKET_STATUS`
     * - agent 相关 topic
     */
    private fun handleGlobalEvent(event: WsEvent) {
        when (event.topic) {
            WsTopic.AGENT_SESSION -> {
                if (event.action == WsAction.SYNC) {
                    // 记录成功创建或恢复的 sessionId
                    activeAgentSessionId = event.targetId
                    println("[GlobalWebSocket] Agent session active: $activeAgentSessionId")
                } else if (event.action == WsAction.ERROR) {
                    // 如果是旧会话订阅失败（说明后端已清理），则自动开启新会话
                    if (event.targetId == activeAgentSessionId) {
                        println("[GlobalWebSocket] Agent session $activeAgentSessionId has expired or failed, creating new one...")
                        activeAgentSessionId = null
                        createAgentSession()
                    }
                }
            }
            WsTopic.INTRADAY_SNAPSHOT -> {
                when (event.action) {
                    WsAction.SYNC, WsAction.UPDATE -> {
                        try {
                            event.payload?.let { payloadJson ->
                                debugLog { "[GlobalWebSocket] Raw intraday payload: $payloadJson" }
                                val payload = json.decodeFromString<IntradaySnapshotPayload>(payloadJson)
                                _intradaySnapshotFlow.value = payload
                                _intradaySnapshotErrorFlow.value = null
                                val selectedCount = payload.portfolio.count { it.selected }
                                debugLog {
                                    "[GlobalWebSocket] Intraday snapshot received: " +
                                        "sentiment=${payload.sentiment.sentimentExposure}, " +
                                        "selected=$selectedCount, portfolio=${payload.portfolio.size}, " +
                                        "topStocks=${payload.topStocks.size}"
                                }
                            }
                        } catch (e: Exception) {
                            println("[GlobalWebSocket] Failed to parse IntradaySnapshot payload: ${e.message}")
                        }
                    }
                    WsAction.ERROR -> {
                        _intradaySnapshotErrorFlow.value = event.payload ?: "盘中快照订阅失败"
                        println("[GlobalWebSocket] Intraday snapshot error: ${event.payload}")
                    }
                    else -> Unit
                }
            }
            WsTopic.STRATEGY_POSITIONS -> {
                when (event.action) {
                    WsAction.SYNC, WsAction.UPDATE -> {
                        try {
                            event.payload?.let { payloadJson ->
                                val snapshot = json.decodeFromString<StrategyPositionSnapshot>(payloadJson)
                                _strategyPositionsFlow.value = snapshot
                                debugLog {
                                    "[GlobalWebSocket] Strategy positions ${event.action}: " +
                                        "tradeDate=${snapshot.tradeDate}, count=${snapshot.currentPositions.size}, " +
                                        "nextSelections=${snapshot.nextSessionSelections.size}, newlySelected=${snapshot.newlySelected.size}, " +
                                        "source=${snapshot.source}"
                                }
                            }
                        } catch (e: Exception) {
                            println("[GlobalWebSocket] Failed to parse StrategyPositionSnapshot: ${e.message}")
                        }
                    }
                    else -> Unit
                }
            }
            WsTopic.STRATEGY_POSITION_TRACKING -> {
                when (event.action) {
                    WsAction.SYNC, WsAction.UPDATE -> {
                        try {
                            event.payload?.let { payloadJson ->
                                val tracking = json.decodeFromString<model.candle.StrategyPositionTrackingResponse>(payloadJson)
                                _strategyPositionTrackingFlow.value = tracking
                                debugLog {
                                    "[GlobalWebSocket] STRATEGY_POSITION_TRACKING ${event.action}: " +
                                        "days=${tracking.days.size}, last=${tracking.days.lastOrNull()?.tradeDate}"
                                }
                            }
                        } catch (e: Exception) {
                            println("[GlobalWebSocket] Failed to parse STRATEGY_POSITION_TRACKING payload: ${e.message}")
                        }
                    }
                    WsAction.ERROR -> {
                        debugLog { "[GlobalWebSocket] STRATEGY_POSITION_TRACKING error: ${event.payload}" }
                    }
                    else -> Unit
                }
            }
            WsTopic.CANDLE_DATA -> {
                val payloadJson = event.payload ?: return
                val candleEvent: CandleStreamEvent? = when (event.action) {
                    WsAction.SYNC, WsAction.UPDATE -> CandleStreamEvent.Data(payloadJson)
                    WsAction.ERROR -> runCatching {
                        CandleStreamEvent.Error(json.decodeFromString<CandleErrorPayload>(payloadJson))
                    }.onFailure { e ->
                        println(
                            "[GlobalWebSocket] Failed to parse CANDLE_DATA error payload " +
                                "for ${event.targetId}: ${e.message}"
                        )
                    }.getOrNull()
                    else -> null
                }
                if (candleEvent != null) {
                    when (candleEvent) {
                        is CandleStreamEvent.Data -> {
                            // 故意不在这里解码 payload：解码 500 根 K 线会阻塞主线程，
                            // 推迟到 ViewModel collectLatest body 内做，旧订阅 cancel 时直接跳过。
                            debugLog {
                                "[GlobalWebSocket] CANDLE_DATA received target=${event.targetId}, " +
                                    "action=${event.action}, rawBytes=${payloadJson.length}"
                            }
                        }
                        is CandleStreamEvent.Error -> {
                            debugLog {
                                "[GlobalWebSocket] CANDLE_DATA error target=${event.targetId}, " +
                                    "code=${candleEvent.payload.errorCode}, " +
                                    "requestSeq=${candleEvent.payload.requestSeq}"
                            }
                        }
                    }
                    _candleEventsFlow.tryEmit(
                        CandleStreamEnvelope(
                            targetId = event.targetId,
                            action = event.action,
                            event = candleEvent
                        )
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * 主动断开全局连接
     * 通常在 App 退到后台时调用。
     */
    fun disconnect() {
        isManualDisconnect = true
        scope.launch {
            println("[GlobalWebSocket] Actively disconnecting multiplexing channel...")
            webSocketSession?.close()
            webSocketSession = null
            isConnected = false
            connectJob?.cancel()
            _connectionStateFlow.value = ConnectionState.DISCONNECTED
            println("[GlobalWebSocket] Disconnected actively by client.")
        }
    }

    /**
     * 发送控制指令给后端
     * @param command 控制指令，如订阅/取消订阅。
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun sendCommand(command: WsCommand) {
        val commandWithSeq = if (command.commandSeq == null) {
            command.copy(commandSeq = commandSeqGenerator.incrementAndFetch())
        } else {
            command
        }
        traceCandleCommand("COMMAND_QUEUED", commandWithSeq)
        if (!isConnected && commandWithSeq.isRestorableStateCommand()) {
            debugLog {
                "[GlobalWebSocket] Deferred restorable command until reconnect: " +
                    "${commandWithSeq.command} (Target: ${commandWithSeq.targetId}, seq=${commandWithSeq.commandSeq})"
            }
            return
        }
        val result = commandChannel.trySend(commandWithSeq)
        if (result.isSuccess) {
            debugLog { "[GlobalWebSocket] Command queued: ${commandWithSeq.command} (Target: ${commandWithSeq.targetId}, seq=${commandWithSeq.commandSeq})" }
        } else {
            println("[GlobalWebSocket] Failed to queue command: ${commandWithSeq.command}")
        }
    }

    private fun WsCommand.isRestorableStateCommand(): Boolean = when (command) {
        CommandType.SUBSCRIBE_CANDLE,
        CommandType.UNSUBSCRIBE_CANDLE,
        CommandType.SET_STOCK_LIST_CONTEXT -> true
        CommandType.SUBSCRIBE,
        CommandType.UNSUBSCRIBE -> targetId == WsTopic.INTRADAY_SNAPSHOT.name ||
            targetId == WsTopic.STRATEGY_POSITIONS.name ||
            targetId == WsTopic.STRATEGY_POSITION_TRACKING.name
        else -> false
    }

    /** 向服务器订阅特定策略选股的流日志 */
    fun subscribeStrategy(taskId: String) {
        sendCommand(WsCommand(CommandType.SUBSCRIBE_STRATEGY, taskId))
    }

    /** 取消订阅特定策略选股的流日志 */
    fun unsubscribeStrategy(taskId: String) {
        sendCommand(WsCommand(CommandType.UNSUBSCRIBE_STRATEGY, taskId))
    }

    /** 创建新的 Agent 会话，workDir 为工作目录（可选，默认用服务器配置） */
    fun createAgentSession(workDir: String? = null) {
        sendCommand(WsCommand(CommandType.AGENT_CREATE_SESSION, payload = workDir))
    }

    /** 关闭 Agent 会话 */
    fun closeAgentSession(sessionId: String) {
        sendCommand(WsCommand(CommandType.AGENT_CLOSE_SESSION, sessionId))
    }

    /** 订阅特定 Agent 会话的状态流 */
    fun subscribeAgent(sessionId: String) {
        sendCommand(WsCommand(CommandType.SUBSCRIBE_AGENT, sessionId))
    }

    /** 取消订阅特定 Agent 会话 */
    fun unsubscribeAgent(sessionId: String) {
        sendCommand(WsCommand(CommandType.UNSUBSCRIBE_AGENT, sessionId))
    }

    /** 发送 Prompt 给 Agent（支持上下文） */
    fun sendAgentPrompt(sessionId: String, prompt: String, context: AgentAnalysisContext? = null) {
        val payload = json.encodeToString(AgentPromptRequest(prompt, context))
        sendCommand(WsCommand(CommandType.AGENT_SEND_PROMPT, sessionId, payload = payload))
    }

    /** 批准 Agent 工具调用 */
    fun approveAgentTool(sessionId: String, requestId: String) {
        sendCommand(WsCommand(CommandType.AGENT_APPROVE_TOOL, sessionId, payload = requestId))
    }

    /** 拒绝 Agent 工具调用 */
    fun rejectAgentTool(sessionId: String, requestId: String) {
        sendCommand(WsCommand(CommandType.AGENT_REJECT_TOOL, sessionId, payload = requestId))
    }

    /** 主动停止 Agent 当前执行任务（不关闭 Session） */
    fun stopAgentSession(sessionId: String) {
        sendCommand(WsCommand(CommandType.AGENT_STOP, sessionId))
    }

    /** 在已被中断的 Agent 会话上以原上下文继续生成 */
    fun resumeAgentSession(sessionId: String) {
        sendCommand(WsCommand(CommandType.AGENT_RESUME, sessionId))
    }

    // ==================== 蜡烛图数据订阅 ====================

    /**
     * 订阅蜡烛图历史数据（CANDLE_DATA topic，用于 SYNC 初始全量）
     */
    fun subscribeCandle(
        tsCode: String,
        period: CandlePeriod = CandlePeriod.DAY,
        limit: Int? = null,
        startDate: String? = null,
        endDate: String? = null,
        useAdjusted: Boolean = true,
        requestSeq: Long? = null
    ) {
        val request = CandleSubscribeRequest(
            tsCode = tsCode,
            period = period,
            limit = limit,
            startDate = startDate,
            endDate = endDate,
            useAdjusted = useAdjusted,
            requestSeq = requestSeq
        )
        val key = "$tsCode:${period.name}"
        activeCandleSubscriptions[key] = request
        CandleTraceLogger.log(
            stage = "SUBSCRIBE_REQUESTED",
            tsCode = tsCode,
            period = period,
            requestSeq = requestSeq,
            detail = "limit=$limit, adjusted=$useAdjusted, start=$startDate, end=$endDate"
        )
        sendCommand(WsCommand(
            CommandType.SUBSCRIBE_CANDLE,
            targetId = tsCode,
            payload = json.encodeToString(request)
        ))
    }

    /** 取消订阅蜡烛图历史数据 */
    fun unsubscribeCandle(tsCode: String, period: CandlePeriod = CandlePeriod.DAY) {
        activeCandleSubscriptions.remove("$tsCode:${period.name}")
        val payload = json.encodeToString(CandleSubscribeRequest(tsCode = tsCode, period = period))
        sendCommand(WsCommand(CommandType.UNSUBSCRIBE_CANDLE, targetId = tsCode, payload = payload))
    }

    /**
     * K 线完整视图事件流（SYNC / UPDATE）。
     *
     * 目标：
     * - `CANDLE_DATA` 成为前端 K 线唯一输入流
     * - 所有周期都通过同一个 topic 获取完整可消费视图
     */
    fun candleEventsFlow(tsCode: String? = null, period: CandlePeriod? = null) = eventsFlow
        .let { _candleEventsFlow.asSharedFlow() }
        .filter {
            val targetId = it.targetId ?: return@filter tsCode == null && period == null
            (tsCode == null || targetId.startsWith("$tsCode:")) &&
                (period == null || targetId.endsWith(":${period.name}"))
        }
        .map { it.event }

    /**
     * 仅对 DAY 的 candle 订阅命令打印链路 trace。
     *
     * 这里故意只解析 `SUBSCRIBE_CANDLE`：
     * - 用户当前只要求日线链路
     * - 其余 topic 的普通控制日志已经足够，没必要把调试维度混在一起
     */
    private fun traceCandleCommand(stage: String, command: WsCommand) {
        if (command.command != CommandType.SUBSCRIBE_CANDLE) return
        val request = runCatching {
            command.payload?.let { json.decodeFromString<CandleSubscribeRequest>(it) }
        }.getOrNull() ?: return
        CandleTraceLogger.log(
            stage = stage,
            tsCode = request.tsCode,
            period = request.period,
            requestSeq = request.requestSeq,
            detail = "commandSeq=${command.commandSeq}, targetId=${command.targetId}"
        )
    }

    /**
     * 市场状态变化事件流
     */
    val marketStatusFlow get() = eventsFlow
        .filter { it.topic == WsTopic.MARKET_STATUS }
        .map { event ->
            try {
                event.payload?.let { json.decodeFromString<MarketStatusPayload>(it) }
            } catch (e: Exception) {
                println("[GlobalWebSocket] Failed to parse MarketStatus payload: ${e.message}")
                null
            }
        }
        .filter { it != null }
        .map { it!! }

    /**
     * 股票列表实时行情更新推送流（topic=STOCK_LIST_UPDATE，1秒/次）
     */
    val stockListUpdateFlow get() = eventsFlow
        .filter { it.topic == WsTopic.STOCK_LIST_UPDATE }
        .mapNotNull { event ->
            try {
                event.payload?.let { json.decodeFromString<StockListUpdatePayload>(it) }
            } catch (e: Exception) {
                println("[GlobalWebSocket] Failed to parse StockListUpdate payload: ${e.message}")
                null
            }
        }

    /**
     * 更新当前页面可见的股票列表上下文
     * 后端将根据此列表开启 1s/次的实时行情推送
     */
    fun setStockListContext(tsCodes: List<String>) {
        activeStockListContext = tsCodes
        val payload = tsCodes.joinToString(",")
        sendCommand(WsCommand(
            CommandType.SET_STOCK_LIST_CONTEXT,
            payload = payload
        ))
    }

    // ==================== 盘中快照数据订阅 ====================

    /**
     * 订阅盘中快照数据（INTRADAY_SNAPSHOT topic）
     * 接收实时市场情绪、盘中选股候选和股票因子数据
     */
    fun subscribeIntradaySnapshot(owner: String = "default") {
        intradaySnapshotOwners.add(owner)
        sendCommand(WsCommand(
            CommandType.SUBSCRIBE,
            targetId = WsTopic.INTRADAY_SNAPSHOT.name
        ))
        println("[GlobalWebSocket] INTRADAY_SNAPSHOT subscribed by $owner, owners=${intradaySnapshotOwners.size}")
    }

    /**
     * 取消订阅盘中快照数据
     */
    fun unsubscribeIntradaySnapshot(owner: String = "default") {
        intradaySnapshotOwners.remove(owner)
        if (intradaySnapshotOwners.isEmpty()) {
            sendCommand(WsCommand(
                CommandType.UNSUBSCRIBE,
                targetId = WsTopic.INTRADAY_SNAPSHOT.name
            ))
            println("[GlobalWebSocket] Unsubscribed from INTRADAY_SNAPSHOT")
        } else {
            println("[GlobalWebSocket] INTRADAY_SNAPSHOT owner removed: $owner, owners=${intradaySnapshotOwners.size}")
        }
    }

    // ==================== 策略持仓轻量状态订阅 ====================

    fun subscribeStrategyPositions(owner: String = "default") {
        strategyPositionsOwners.add(owner)
        sendCommand(WsCommand(
            CommandType.SUBSCRIBE,
            targetId = WsTopic.STRATEGY_POSITIONS.name
        ))
        println("[GlobalWebSocket] STRATEGY_POSITIONS subscribed by $owner, owners=${strategyPositionsOwners.size}")
    }

    fun unsubscribeStrategyPositions(owner: String = "default") {
        strategyPositionsOwners.remove(owner)
        if (strategyPositionsOwners.isEmpty()) {
            sendCommand(WsCommand(
                CommandType.UNSUBSCRIBE,
                targetId = WsTopic.STRATEGY_POSITIONS.name
            ))
            println("[GlobalWebSocket] Unsubscribed from STRATEGY_POSITIONS")
        } else {
            println("[GlobalWebSocket] STRATEGY_POSITIONS owner removed: $owner, owners=${strategyPositionsOwners.size}")
        }
    }

    // ==================== 策略持仓跟踪订阅 ====================

    /**
     * 订阅策略持仓跟踪数据（STRATEGY_POSITION_TRACKING topic）
     * 接收预计算的完整时间线（含历史 PNL + 盘中实时数据）
     */
    fun subscribeStrategyPositionTracking(owner: String = "default") {
        strategyPositionTrackingOwners.add(owner)
        sendCommand(WsCommand(
            CommandType.SUBSCRIBE,
            targetId = WsTopic.STRATEGY_POSITION_TRACKING.name
        ))
        println("[GlobalWebSocket] Strategy position tracking subscribed by $owner, owners=${strategyPositionTrackingOwners.size}")
    }

    fun refreshStrategyPositionTracking(owner: String = "default") {
        strategyPositionTrackingOwners.add(owner)
        sendCommand(WsCommand(
            CommandType.SUBSCRIBE,
            targetId = WsTopic.STRATEGY_POSITION_TRACKING.name
        ))
        println("[GlobalWebSocket] Strategy position tracking refresh requested by $owner")
    }

    /** 设置策略持仓跟踪的起始跟随日期 */
    fun setTrackingFollowStartDate(followStartDate: String) {
        sendCommand(WsCommand(
            CommandType.SET_TRACKING_FOLLOW_START_DATE,
            payload = followStartDate
        ))
    }

    /**
     * 取消订阅策略持仓跟踪数据
     */
    fun unsubscribeStrategyPositionTracking(owner: String = "default") {
        strategyPositionTrackingOwners.remove(owner)
        if (strategyPositionTrackingOwners.isEmpty()) {
            sendCommand(WsCommand(
                CommandType.UNSUBSCRIBE,
                targetId = WsTopic.STRATEGY_POSITION_TRACKING.name
            ))
            println("[GlobalWebSocket] Unsubscribed from STRATEGY_POSITION_TRACKING")
        } else {
            println("[GlobalWebSocket] Strategy position tracking owner removed: $owner, owners=${strategyPositionTrackingOwners.size}")
        }
    }
}

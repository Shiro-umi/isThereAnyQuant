package org.shiroumi.server.websocket

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.ws.CandleErrorCode
import model.ws.CommandType
import model.ws.WsCommand
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.database.user.createUserRepository
import org.shiroumi.database.user.repository.UserRepository
import org.shiroumi.server.data.trace.CandleTrace
import org.shiroumi.server.dataprovider.bootstrap.DataProviderBootstrap
import org.shiroumi.server.subscription.intraday.IntradaySnapshotSubscriptionService
import org.shiroumi.server.websocket.service.AgentWebSocketService
import utils.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局 WebSocket 连接管理器 (Multiplexing Manager)
 * 
 * 职责：
 * 1. 维护当前所有处于活跃状态的 WebSocket 客户端连接。
 * 2. 维护每个客户端对具体任务数据流的「订阅关系」。
 * 3. 提供全局广播 (`broadcast`) 与按需推送 (`sendToSubscribers`) 的发送能力。
 */
object AppWebSocketConnectionManager {
    private val logger by logger("AppWebSocketConnectionManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // FIFO 主要承载 AGENT_STREAM 这类「不能折叠的离散增量」。一份长报告的细粒度
    // chunk（文本 chunk + 工具事件 + 状态变化）量级在几百，1024 提供足够的吞吐缓冲；
    // 真到溢出说明 socket 已经完全发不出去，触发 slowSessionClose 是正确处置。
    private const val OUTBOUND_FIFO_CAPACITY = 1024
    private const val OUTBOUND_CONFLATED_KEY_CAPACITY = 256
    private const val OUTBOUND_SEND_TIMEOUT_MS = 5_000L
    private const val COMMAND_CHANNEL_CAPACITY = 256
    
    data class SessionData(
        val userId: UUID,
        val subscriptions: MutableSet<String> = CopyOnWriteArraySet(),
        val closing: AtomicBoolean = AtomicBoolean(false),
        val outboundMailbox: OutboundMailbox = OutboundMailbox(
            fifoCapacity = OUTBOUND_FIFO_CAPACITY,
            conflatedKeyCapacity = OUTBOUND_CONFLATED_KEY_CAPACITY
        ),
        @Volatile var outboundJob: Job? = null,
        val commandChannel: Channel<WsCommand> = Channel(COMMAND_CHANNEL_CAPACITY),
        @Volatile var commandProcessorJob: Job? = null,
        /**
         * 当前会话内需要随连接关闭统一取消的命令处理任务。
         *
         * 设计目标：
         * 1. 收包线程只入队，不被耗时命令阻塞
         * 2. 同一 session 的命令由单一 processor 串行执行
         * 3. 连接关闭时可以统一取消未完成任务
         */
        val activeCommandJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
        /**
         * 会话内“收敛键 -> 最新命令序号”。
         *
         * 当同一类命令被快速连续发送时：
         * - 新命令会覆盖该收敛键的最新序号
         * - 旧命令即便晚完成，也会在提交前被识别为 stale 并丢弃
         */
        val latestCommandSeqByKey: MutableMap<String, Long> = ConcurrentHashMap()
    )

    // 映射关系：WebSocketSession -> 会话绑定的用户与订阅状态
    private val connections = ConcurrentHashMap<DefaultWebSocketServerSession, SessionData>()

    // 用户 -> 当前活跃 WebSocketSession。用于按 topic 广播时避免反复扫描全连接表。
    private val sessionsByUserId = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    // Topic 订阅映射: Key=Topic, Value=Set<userId>
    private val topicSubscriptions = ConcurrentHashMap<WsTopic, MutableSet<String>>()

    // 反向索引：targetId → 订阅该 targetId 的 session 集合
    // 用于 sendToSubscribers 直接 lookup，避免每次遍历全部 connections
    private val targetSubscribers = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    // JSON 序列化配置
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 用户仓库，用于处理 SET_TRACKING_FOLLOW_START_DATE 等用户级设置命令
    private val userRepository: UserRepository by lazy { createUserRepository() }

    /**
     * 添加新的客户端连接，绑定鉴权通过的 userId并初始化其空的订阅集合
     */
    fun addConnection(session: DefaultWebSocketServerSession, userId: UUID) {
        val sessionData = SessionData(userId = userId)
        connections[session] = sessionData
        sessionData.outboundJob = startOutboundSender(session, sessionData)
        sessionData.commandProcessorJob = startCommandProcessor(session, sessionData)
        sessionsByUserId.computeIfAbsent(userId.toString()) { ConcurrentHashMap.newKeySet() }
            .add(session)
        logger.info("New global WebSocket connection established for user $userId. Total multiplexing connections: ${connections.size}")
        
        // 当新连接建立时，主动推送一次当前的数据更新状态
        val currentStatus = org.shiroumi.server.service.DataUpdateService.getCurrentStatus()
        scope.launch {
            sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.DATA_UPDATE,
                    action = model.ws.WsAction.UPDATE,
                    payload = json.encodeToString(currentStatus)
                )
            )
            sendToSession(
                session,
                WsEvent(
                    topic = WsTopic.MARKET_STATUS,
                    action = model.ws.WsAction.UPDATE,
                    payload = json.encodeToString(DataProviderBootstrap.marketStatusProjectionService.currentPayload())
                )
            )
        }
    }

    /**
     * 移除断开的客户端连接，自动清理其订阅记录
     */
    suspend fun removeConnection(session: DefaultWebSocketServerSession) {
        // 1. 先从连接池中移除，确保 getSubscriberCount 能够统计到正确（减少后）的人数
        val sessionData = connections.remove(session)

        if (sessionData != null) {
            sessionData.closing.set(true)
            sessionData.activeCommandJobs.forEach { it.cancel() }
            sessionData.commandChannel.close()
            sessionData.commandProcessorJob?.cancel()
            sessionData.outboundMailbox.close()
            sessionData.outboundJob?.cancel()
            val userIdStr = sessionData.userId.toString()
            sessionsByUserId[userIdStr]?.let { sessions ->
                sessions.remove(session)
                if (sessions.isEmpty()) {
                    sessionsByUserId.remove(userIdStr, sessions)
                }
            }

            // 2. 通知 AgentWebSocketService 订阅者离开，以便触发可能的清理逻辑
            // 同时清理反向索引
            sessionData.subscriptions.forEach { targetId ->
                // 我们无法直接判断 targetId 是否为 Agent Session ID，
                // 但 AgentWebSocketService 会内部检查其活跃会话列表
                AgentWebSocketService.onSubscriberLeft(targetId)
                targetSubscribers[targetId]?.remove(session)
                cleanupTargetSubscriberEntry(targetId)
            }

            // 3. 从所有 Topic 订阅中移除该用户
            topicSubscriptions.forEach { (topic, userIds) ->
                userIds.remove(userIdStr)
                if (userIds.isEmpty()) {
                    topicSubscriptions.remove(topic, userIds)
                }
            }
        }

        // 4. 清理 K 线与盘中快照订阅
        DataProviderBootstrap.candleSubscriptionService.cleanupSession(session)
        DataProviderBootstrap.intradaySnapshotSubscriptionService.cleanupSession(session)
        DataProviderBootstrap.strategyPositionTrackingSubscriptionService.cleanupSession(session)
        DataProviderBootstrap.strategyPositionSubscriptionService.cleanupSession(session)

        // 5. 清理股票列表页面上下文
        DataProviderBootstrap.stockListContextRuntimeService.cleanupSession(session)

        logger.info("Global WebSocket connection removed. Remaining multiplexing connections: ${connections.size}")
    }

    /**
     * 用户订阅 Topic
     */
    fun subscribe(userId: String, topic: WsTopic) {
        topicSubscriptions.computeIfAbsent(topic) { ConcurrentHashMap.newKeySet() }
            .add(userId)
        logger.info("User $userId subscribed to topic: $topic")
    }

    /**
     * 用户取消订阅 Topic
     */
    fun unsubscribe(userId: String, topic: WsTopic) {
        topicSubscriptions[topic]?.let { userIds ->
            userIds.remove(userId)
            if (userIds.isEmpty()) {
                topicSubscriptions.remove(topic, userIds)
            }
        }
        logger.info("User $userId unsubscribed from topic: $topic")
    }

    /**
     * 广播消息到指定 Topic 的所有订阅用户
     */
    suspend fun broadcastToTopic(topic: WsTopic, event: WsEvent) {
        logger.info("[broadcastToTopic] Broadcasting to topic: $topic")

        val userIds = topicSubscriptions[topic]
        if (userIds == null) {
            logger.warning("[broadcastToTopic] No subscriptions found for topic: $topic")
            return
        }

        if (userIds.isEmpty()) {
            logger.warning("[broadcastToTopic] Topic $topic has empty subscriber set")
            return
        }

        logger.info("[broadcastToTopic] Found ${userIds.size} subscribers for topic: $topic")

        if (connections.isEmpty()) {
            logger.warning("[broadcastToTopic] No active WebSocket connections")
            return
        }

        logger.info("[broadcastToTopic] Active connections: ${connections.size}")

        var sentCount = 0
        var failedCount = 0
        val envelope = event.toOutboundEnvelope()
        userIds.forEach { userId ->
            val sessions = sessionsByUserId[userId].orEmpty()
            sessions.forEach { session ->
                try {
                    enqueueEnvelopeToSession(session, envelope, event, "topic:$topic")
                    sentCount++
                    // logger.debug("[broadcastToTopic] Sent event to user: $userId")
                } catch (e: Exception) {
                    failedCount++
                    logger.error("[broadcastToTopic] Failed to send event to user $userId: ${e.message}")
                }
            }
        }

        logger.info("[broadcastToTopic] Broadcast complete for topic: $topic, sent=$sentCount, failed=$failedCount")
    }

    /**
     * 处理客户端发送的控制指令（订阅/取消订阅）
     */
    fun handleCommand(session: DefaultWebSocketServerSession, command: WsCommand) {
        val sessionData = connections[session] ?: return
        val convergenceKey = command.convergenceKey()
        val commandSeq = command.commandSeq
        if (convergenceKey != null && commandSeq != null) {
            sessionData.latestCommandSeqByKey.compute(convergenceKey) { _, current ->
                maxOf(current ?: Long.MIN_VALUE, commandSeq)
            }
        }
        if (sessionData.isStale(command)) return
        if (sessionData.closing.get()) return
        val result = sessionData.commandChannel.trySend(command)
        if (result.isFailure) {
            logger.warning(
                "WebSocket command channel overflow or closed; closing session=${session.hashCode()}, " +
                    "command=${command.command}, target=${command.targetId}"
            )
            scheduleSlowSessionClose(session, "command channel overflow")
        }
    }

    /**
     * 在会话级命令队列中串行处理单条命令。
     *
     * 这样可以同时满足两个目标：
     * - 同一会话内的命令顺序稳定
     * - `incoming.consumeEach` 不会因为 provider 激活或历史加载而被阻塞
     */
    private suspend fun processCommand(session: DefaultWebSocketServerSession, command: WsCommand) {
        val sessionData = connections[session] ?: return
        if (sessionData.isStale(command)) return
        val subs = sessionData.subscriptions
        when (command.command) {
            model.ws.CommandType.SUBSCRIBE_STRATEGY -> {
                command.targetId?.let { 
                    subs.add(it)
                    targetSubscribers.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(session)
                    logger.info("Client Session subscribed to Strategy task stream: $it")
                }
            }
            model.ws.CommandType.UNSUBSCRIBE_STRATEGY -> {
                command.targetId?.let { 
                    subs.remove(it)
                    targetSubscribers[it]?.remove(session)
                    cleanupTargetSubscriberEntry(it)
                    logger.info("Client Session unsubscribed from Strategy task stream: $it")
                }
            }
            model.ws.CommandType.FORCE_REFRESH_LIST -> {
                logger.info("Client requested forced list refresh. (To be handled if necessary)")
            }
            model.ws.CommandType.AGENT_CREATE_SESSION -> {
                AgentWebSocketService.createSession(session, command.payload, sessionData.userId)
                logger.info("Agent session creation requested by user ${sessionData.userId}")
            }
            model.ws.CommandType.AGENT_CLOSE_SESSION -> {
                command.targetId?.let {
                    AgentWebSocketService.closeSession(it)
                    logger.info("Agent session close requested: $it")
                }
            }
            model.ws.CommandType.SUBSCRIBE_AGENT -> {
                command.targetId?.let {
                    subs.add(it)
                    targetSubscribers.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(session)
                    AgentWebSocketService.onSubscriberJoined(it)
                    // 订阅一建立就推一次完整快照。这条 SYNC 同时承担两个职责：
                    // 1. 首次进入会话时立刻渲染当前累积状态
                    // 2. 断线重连后的"恢复 baseline"——客户端用它替换本地聚合，
                    //    随后到达的 Delta 是相对该快照的增量
                    AgentWebSocketService.sendInitialSnapshot(session, it)
                    logger.info("Client subscribed to agent stream: $it")
                }
            }
            model.ws.CommandType.UNSUBSCRIBE_AGENT -> {
                command.targetId?.let {
                    subs.remove(it)
                    targetSubscribers[it]?.remove(session)
                    cleanupTargetSubscriberEntry(it)
                    AgentWebSocketService.onSubscriberLeft(it)
                    logger.info("Client unsubscribed from agent stream: $it")
                }
            }
            model.ws.CommandType.AGENT_SEND_PROMPT -> {
                val targetId = command.targetId
                val prompt = command.payload
                if (targetId != null && prompt != null) {
                    AgentWebSocketService.sendPrompt(targetId, prompt)
                    logger.info("Agent prompt sent to session: $targetId")
                }
            }
            model.ws.CommandType.AGENT_APPROVE_TOOL -> {
                val sessionId = command.targetId
                val requestId = command.payload
                if (sessionId != null && requestId != null) {
                    AgentWebSocketService.approveTool(sessionId, requestId)
                    logger.info("Tool approved for session: $sessionId, request: $requestId")
                }
            }
            model.ws.CommandType.AGENT_REJECT_TOOL -> {
                val sessionId = command.targetId
                val requestId = command.payload
                if (sessionId != null && requestId != null) {
                    AgentWebSocketService.rejectTool(sessionId, requestId)
                    logger.info("Tool rejected for session: $sessionId, request: $requestId")
                }
            }
            model.ws.CommandType.AGENT_STOP -> {
                command.targetId?.let {
                    AgentWebSocketService.stopSession(it)
                    logger.info("Agent stop requested for session: $it")
                }
            }
            model.ws.CommandType.AGENT_RESUME -> {
                command.targetId?.let {
                    AgentWebSocketService.resumeSession(it)
                    logger.info("Agent resume requested for session: $it")
                }
            }
            model.ws.CommandType.SUBSCRIBE_CANDLE -> {
                val tsCode = command.targetId
                if (tsCode != null) {
                    if (sessionData.isStale(command)) return
                    if (!DataProviderBootstrap.serverLifecycleManager.canAcceptBusinessSubscriptions()) {
                        DataProviderBootstrap.candleSubscriptionService.sendSubscriptionError(
                            session = session,
                            tsCode = tsCode,
                            payloadJson = command.payload,
                            errorCode = CandleErrorCode.SYSTEM_WARMING_UP,
                            message = "服务正在初始化，请稍后重试"
                        )
                        return
                    }
                    try {
                        val traceRequest = runCatching {
                            DataProviderBootstrap.candleSubscriptionService.resolveRequest(tsCode, command.payload)
                        }.getOrNull()
                        if (traceRequest != null) {
                            CandleTrace.log(
                                stage = "WS_COMMAND_RECEIVED",
                                tsCode = traceRequest.tsCode,
                                period = traceRequest.period,
                                requestSeq = traceRequest.requestSeq,
                                detail = "commandSeq=${command.commandSeq}, session=${session.hashCode()}"
                            )
                        }
                        DataProviderBootstrap.candleSubscriptionService.subscribe(session, tsCode, command.payload)
                    } catch (error: Exception) {
                        logger.error("Candle subscription failed: $tsCode, ${error.message}")
                        DataProviderBootstrap.candleSubscriptionService.sendSubscriptionError(
                            session = session,
                            tsCode = tsCode,
                            payloadJson = command.payload,
                            errorCode = CandleErrorCode.PROVIDER_NOT_READY,
                            message = error.message ?: "K线订阅初始化失败"
                        )
                    }
                }
            }
            model.ws.CommandType.UNSUBSCRIBE_CANDLE -> {
                val tsCode = command.targetId
                if (tsCode != null) {
                    if (sessionData.isStale(command)) return
                    DataProviderBootstrap.candleSubscriptionService.unsubscribe(session, tsCode, command.payload)
                }
            }
            model.ws.CommandType.SET_STOCK_LIST_CONTEXT -> {
                // payload 为 tsCodes 以逗号分隔的字符串
                val payload = command.payload
                if (payload != null) {
                    if (sessionData.isStale(command)) return
                    val tsCodes = payload.split(",").filter { it.isNotBlank() }
                    DataProviderBootstrap.stockListContextRuntimeService.updateVisibleStocks(session, tsCodes)
                }
            }
            model.ws.CommandType.SUBSCRIBE -> {
                val topicName = command.targetId
                if (topicName != null) {
                    runCatching {
                        val topic = WsTopic.valueOf(topicName)
                        if (sessionData.isStale(command)) {
                            return@runCatching
                        }
                        if (topic == WsTopic.INTRADAY_SNAPSHOT) {
                            DataProviderBootstrap.intradaySnapshotSubscriptionService.subscribe(session)
                            return@runCatching
                        }
                        if (topic == WsTopic.STRATEGY_POSITIONS) {
                            DataProviderBootstrap.strategyPositionSubscriptionService.subscribe(session)
                            return@runCatching
                        }
                        if (topic == WsTopic.STRATEGY_POSITION_TRACKING) {
                            DataProviderBootstrap.strategyPositionTrackingSubscriptionService.subscribe(session)
                            return@runCatching
                        }
                        val userId = sessionData.userId.toString()
                        subscribe(userId, topic)
                    }.onFailure {
                        logger.warning("Invalid topic name for SUBSCRIBE: $topicName")
                    }
                }
            }
            model.ws.CommandType.UNSUBSCRIBE -> {
                val topicName = command.targetId
                if (topicName != null) {
                    runCatching {
                        val topic = WsTopic.valueOf(topicName)
                        if (sessionData.isStale(command)) {
                            return@runCatching
                        }
                        if (topic == WsTopic.INTRADAY_SNAPSHOT) {
                            DataProviderBootstrap.intradaySnapshotSubscriptionService.unsubscribe(session)
                            return@runCatching
                        }
                        if (topic == WsTopic.STRATEGY_POSITIONS) {
                            DataProviderBootstrap.strategyPositionSubscriptionService.unsubscribe(session)
                            return@runCatching
                        }
                        if (topic == WsTopic.STRATEGY_POSITION_TRACKING) {
                            DataProviderBootstrap.strategyPositionTrackingSubscriptionService.unsubscribe(session)
                            return@runCatching
                        }
                        val userId = sessionData.userId.toString()
                        unsubscribe(userId, topic)
                    }.onFailure {
                        logger.warning("Invalid topic name for UNSUBSCRIBE: $topicName")
                    }
                }
            }
            model.ws.CommandType.SET_TRACKING_FOLLOW_START_DATE -> {
                if (sessionData.isStale(command)) return
                val followStartDate = command.payload?.takeIf { it.isNotBlank() }
                if (followStartDate != null) {
                    val valid = runCatching {
                        LocalDate.parse(followStartDate)
                        true
                    }.getOrDefault(false)
                    if (!valid) {
                        logger.warning("Invalid follow-start-date format: $followStartDate")
                        return
                    }
                }
                userRepository.setTrackingFollowStartDate(sessionData.userId, followStartDate)
                DataProviderBootstrap.strategyPositionTrackingSubscriptionService.refresh(session)
                logger.info("User ${sessionData.userId} set tracking follow-start-date to $followStartDate")
            }
        }
    }

    private fun startCommandProcessor(
        session: DefaultWebSocketServerSession,
        sessionData: SessionData
    ): Job {
        val job = scope.launch {
            try {
                for (command in sessionData.commandChannel) {
                    if (sessionData.closing.get()) break
                    runCatching {
                        processCommand(session, command)
                    }.onFailure { error ->
                        if (!sessionData.closing.get()) {
                            logger.warning(
                                "WebSocket command processing failed session=${session.hashCode()}, " +
                                    "command=${command.command}, target=${command.targetId}: ${error.message}"
                            )
                        }
                    }
                }
            } finally {
                logger.info("WebSocket command processor stopped for session=${session.hashCode()}")
            }
        }
        sessionData.activeCommandJobs.add(job)
        job.invokeOnCompletion {
            sessionData.activeCommandJobs.remove(job)
        }
        return job
    }

    private fun SessionData.isStale(command: WsCommand): Boolean {
        val convergenceKey = command.convergenceKey() ?: return false
        val seq = command.commandSeq ?: return false
        val latest = latestCommandSeqByKey[convergenceKey] ?: return false
        return seq < latest
    }

    private fun WsCommand.convergenceKey(): String? = when (command) {
        CommandType.SUBSCRIBE_CANDLE,
        CommandType.UNSUBSCRIBE_CANDLE -> candleConvergenceKey()
        CommandType.SET_STOCK_LIST_CONTEXT -> "stock-list-context"
        CommandType.SET_TRACKING_FOLLOW_START_DATE -> "tracking-follow-start-date"
        CommandType.SUBSCRIBE -> topicConvergenceKey("sub")
        CommandType.UNSUBSCRIBE -> topicConvergenceKey("unsub")
        else -> null
    }

    /**
     * Candle 订阅命令的收敛域。
     *
     * 业务事实：同一 session 同一周期同一时刻只展示一只股票的 K 线
     * （`CandleViewModel.selectStock/selectPeriod` 切股切周期都是先 UNSUBSCRIBE 再 SUBSCRIBE）。
     * 收敛 key 不应带 tsCode，否则用户快速点击股票列表时，每只股票被视作独立 key，
     * 旧命令的 `isStale` 检查全部失效，服务端必须为中途点过的每一只股票完整跑一遍
     * 投影 + 序列化 + 下发。
     *
     * SUBSCRIBE 和 UNSUBSCRIBE 共用同一前缀（不再拆 sub/unsub），是因为
     * `CandleDataProvider.subscribe` 在执行时会主动清理本 session 同 period 下其他
     * tsCode 的旧订阅——业务不变量由 provider 自己保证，不需要依赖 UNSUBSCRIBE 命令
     * 一定被执行。这样中间过期的 UNSUBSCRIBE 即使被 stale 丢弃也不会泄漏订阅。
     *
     * 收敛到 period 而非全局，是因为不同周期之间的订阅是叠加关系：
     * 用户在切日线时不应让另一只股票的分钟线订阅被丢弃。
     */
    private fun WsCommand.candleConvergenceKey(): String? {
        val period = payload
            ?.let { raw -> runCatching { json.decodeFromString<model.ws.CandleSubscribeRequest>(raw).period }.getOrNull() }
            ?.name
            ?: return "candle"
        return "candle:$period"
    }

    /**
     * 通用 Topic (SUBSCRIBE/UNSUBSCRIBE) 的收敛域。
     *
     * 与 candleConvergenceKey 同理：subscribe 和 unsubscribe 使用不同的收敛前缀，
     * 避免 unsubscribe 被后续 subscribe 的更高 commandSeq 误判为 stale 而丢弃。
     */
    private fun WsCommand.topicConvergenceKey(prefix: String): String? {
        return targetId
            ?.takeIf {
                it == WsTopic.INTRADAY_SNAPSHOT.name ||
                    it == WsTopic.MARKET_STATUS.name ||
                    it == WsTopic.STRATEGY_POSITIONS.name ||
                    it == WsTopic.STRATEGY_POSITION_TRACKING.name
            }
            ?.let { "topic:$prefix:$it" }
    }

    /**
     * 全局广播
     * 向所有已连接的客户端发送事件。通常用于 `DATA_UPDATE` 或 `*_LIST` 的状态刷新通知。
     */
    suspend fun broadcast(event: WsEvent) {
        val envelope = event.toOutboundEnvelope()
        connections.keys.forEach { session ->
            try {
                enqueueEnvelopeToSession(session, envelope, event, "broadcast:${event.topic}")
            } catch (e: Exception) {
                logger.warning("Failed to send broadcast event to a session: ${e.message}")
            }
        }
    }

    /**
     * 按需推送 (发布-订阅模型)
     * 仅向明确订阅了某个 `targetId` (如具体 taskId) 的客户端推送事件。
     * 通常用于大体量的流式日志，如 `STRATEGY_STREAM` 或 `AGENT_STREAM`。
     *
     * 使用反向索引 targetSubscribers 直接定位，避免全连接表扫描。
     */
    suspend fun sendToSubscribers(topic: WsTopic, targetId: String, event: WsEvent) {
        val envelope = event.toOutboundEnvelope()
        targetSubscribers[targetId]?.forEach { session ->
            try {
                enqueueEnvelopeToSession(session, envelope, event, "subscriber:$topic:$targetId")
            } catch (e: Exception) {
                logger.warning("Failed to send stream event to subscriber of $targetId: ${e.message}")
            }
        }
    }

    /**
     * 向特定会话发送事件
     * 用于服务层直接推送数据到指定客户端
     *
     * 这里故意不再对 CANDLE_DATA payload 二次解码打 trace：
     * 500 根 K 线的反序列化是稳定的几毫秒开销，且热路径每次发送都付一次。
     * Provider 一侧的 `PROVIDER_PAYLOAD_READY` / `PROVIDER_SEND_COMPLETE` 已经带齐
     * tsCode / period / requestSeq / count，足够还原链路。
     */
    suspend fun sendToSession(session: DefaultWebSocketServerSession, event: WsEvent) {
        if (!connections.containsKey(session)) {
            logger.warning("Attempted to send event to unknown session")
            return
        }
        try {
            enqueueEventToSession(session, event, "session:${event.topic}")
        } catch (e: Exception) {
            logger.warning("Failed to send event to session: ${e.message}")
        }
    }

    fun isSessionActive(session: DefaultWebSocketServerSession): Boolean =
        connections[session]?.closing?.get() == false

    /**
     * 根据 session 获取用户 ID。
     *
     * 供策略持仓跟踪等需要在 session 外部获取用户上下文的模块使用。
     */
    internal fun getUserId(session: DefaultWebSocketServerSession): UUID? = connections[session]?.userId

    private fun startOutboundSender(
        session: DefaultWebSocketServerSession,
        sessionData: SessionData
    ): Job = scope.launch {
        try {
            while (true) {
                val message = sessionData.outboundMailbox.receive() ?: break
                if (sessionData.closing.get()) break
                try {
                    withTimeout(OUTBOUND_SEND_TIMEOUT_MS) {
                        session.send(message)
                    }
                } catch (e: Exception) {
                    logger.warning(
                        "WebSocket outbound send failed or timed out; closing slow session=${session.hashCode()}: ${e.message}"
                    )
                    scheduleSlowSessionClose(session, e.message ?: "outbound send failed")
                    break
                }
            }
        } finally {
            logger.info("WebSocket outbound sender stopped for session=${session.hashCode()}")
        }
    }

    private fun enqueueEventToSession(
        session: DefaultWebSocketServerSession,
        event: WsEvent,
        reason: String
    ) {
        enqueueEnvelopeToSession(session, event.toOutboundEnvelope(), event, reason)
    }

    private fun enqueueEnvelopeToSession(
        session: DefaultWebSocketServerSession,
        envelope: OutboundEnvelope,
        event: WsEvent,
        reason: String
    ) {
        val sessionData = connections[session] ?: run {
            logger.warning("Attempted to enqueue event to unknown session")
            return
        }
        if (sessionData.closing.get()) return
        when (val result = sessionData.outboundMailbox.offer(envelope)) {
            OutboundOfferResult.ACCEPTED,
            OutboundOfferResult.CLOSED -> Unit
            OutboundOfferResult.OVERFLOW -> {
                logger.warning(
                    "WebSocket outbound mailbox overflow; closing slow session=${session.hashCode()}, " +
                        "reason=$reason, policy=${result.policyLabel(event)}"
                )
                scheduleSlowSessionClose(session, "outbound mailbox overflow")
            }
        }
    }

    private fun WsEvent.toOutboundEnvelope(): OutboundEnvelope =
        OutboundEnvelope.from(this, json.encodeToString(this))

    private fun OutboundOfferResult.policyLabel(event: WsEvent): String =
        if (event.conflatedSnapshotKey() != null) "conflated" else "fifo"

    data class OutboundEnvelope(
        val message: String,
        val conflatedKey: String?
    ) {
        companion object {
            fun from(event: WsEvent, message: String): OutboundEnvelope =
                OutboundEnvelope(
                    message = message,
                    conflatedKey = event.conflatedSnapshotKey()
                )
        }
    }

    enum class OutboundOfferResult {
        ACCEPTED,
        OVERFLOW,
        CLOSED
    }

    data class OutboundMailboxSnapshot(
        val fifoSize: Int,
        val conflatedSize: Int,
        val closed: Boolean
    )

    class OutboundMailbox(
        private val fifoCapacity: Int,
        private val conflatedKeyCapacity: Int
    ) {
        private val lock = Any()
        private val fifo = ArrayDeque<String>()
        private val conflated = LinkedHashMap<String, String>()
        private val signal = Channel<Unit>(Channel.CONFLATED)
        private var closed = false

        fun offer(envelope: OutboundEnvelope): OutboundOfferResult {
            val accepted = synchronized(lock) {
                if (closed) return@synchronized OutboundOfferResult.CLOSED
                val key = envelope.conflatedKey
                if (key != null) {
                    if (!conflated.containsKey(key) && conflated.size >= conflatedKeyCapacity) {
                        return@synchronized OutboundOfferResult.OVERFLOW
                    }
                    conflated[key] = envelope.message
                    return@synchronized OutboundOfferResult.ACCEPTED
                }
                if (fifo.size >= fifoCapacity) {
                    return@synchronized OutboundOfferResult.OVERFLOW
                }
                fifo.addLast(envelope.message)
                OutboundOfferResult.ACCEPTED
            }
            if (accepted == OutboundOfferResult.ACCEPTED) {
                signal.trySend(Unit)
            }
            return accepted
        }

        suspend fun receive(): String? {
            while (true) {
                poll()?.let { return it }
                if (isClosedAndEmpty()) return null
                if (signal.receiveCatching().isClosed && isClosedAndEmpty()) {
                    return null
                }
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                fifo.clear()
                conflated.clear()
            }
            signal.close()
        }

        fun snapshot(): OutboundMailboxSnapshot = synchronized(lock) {
            OutboundMailboxSnapshot(
                fifoSize = fifo.size,
                conflatedSize = conflated.size,
                closed = closed
            )
        }

        private fun poll(): String? = synchronized(lock) {
            if (fifo.isNotEmpty()) {
                return@synchronized fifo.removeFirst()
            }
            val iterator = conflated.entries.iterator()
            if (iterator.hasNext()) {
                val message = iterator.next().value
                iterator.remove()
                return@synchronized message
            }
            null
        }

        private fun isClosedAndEmpty(): Boolean = synchronized(lock) {
            closed && fifo.isEmpty() && conflated.isEmpty()
        }
    }

    private fun WsEvent.conflatedSnapshotKey(): String? {
        // AGENT_STREAM 故意不做 conflate：
        // 在 Snapshot/Delta 协议下，UPDATE 帧承载的是「自上一帧以来的增量」，
        // 用 latest-only 覆盖会直接丢失 output append / 工具状态变更，
        // 用户看到的就会是「停止后内容一次性涌出」这种错觉。
        // 终态 COMPLETE / ERROR 帧自带 Snapshot 兜底，丢中间帧也不会让最终视图错位，
        // 但中间增量必须按 FIFO 顺序到达才能维持「边生成边看到」的体验。
        if (action != model.ws.WsAction.SYNC && action != model.ws.WsAction.UPDATE) return null
        return when (topic) {
            // CANDLE_DATA 的 targetId 是 "${tsCode}:${period}"；同一 session 在任一时刻
            // 同周期内只看一只 K 线（CandleViewModel.selectStock/selectPeriod 总是先
            // UNSUBSCRIBE 再 SUBSCRIBE），因此快速切股时旧 tsCode 的待发帧应被新 tsCode
            // 的最新帧覆盖。收敛 key 只保留 period 段，跨周期叠加保持独立。
            WsTopic.CANDLE_DATA -> "snapshot:${topic.name}:${candlePeriodSegment(targetId)}"
            WsTopic.STOCK_LIST_UPDATE,
            WsTopic.INTRADAY_SNAPSHOT,
            WsTopic.STRATEGY_POSITIONS,
            WsTopic.STRATEGY_POSITION_TRACKING -> "snapshot:${topic.name}:${targetId ?: topic.name}"
            else -> null
        }
    }

    private fun candlePeriodSegment(targetId: String?): String {
        if (targetId == null) return "ALL"
        val idx = targetId.indexOf(':')
        return if (idx < 0 || idx == targetId.length - 1) targetId else targetId.substring(idx + 1)
    }

    private fun scheduleSlowSessionClose(session: DefaultWebSocketServerSession, reason: String) {
        val sessionData = connections[session] ?: return
        if (!sessionData.closing.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                session.close(
                    CloseReason(
                        CloseReason.Codes.TRY_AGAIN_LATER,
                        reason.take(120)
                    )
                )
            }.onFailure { error ->
                logger.warning("Failed to close slow WebSocket session=${session.hashCode()}: ${error.message}")
            }
            removeConnection(session)
        }
    }

    /**
     * 清理空的 targetSubscriberEntry，避免 ConcurrentHashMap 内存泄漏。
     */
    private fun cleanupTargetSubscriberEntry(targetId: String) {
        targetSubscribers.computeIfPresent(targetId) { _, sessions ->
            if (sessions.isEmpty()) null else sessions
        }
    }

    /**
     * 获取特定 targetId 的当前订阅人数
     */
    fun getSubscriberCount(targetId: String): Int {
        return targetSubscribers[targetId]?.size ?: 0
    }

    fun maxOutboundFifoSizeForTarget(targetId: String): Int =
        targetSubscribers[targetId]
            ?.mapNotNull { session -> connections[session]?.outboundMailbox?.snapshot()?.fifoSize }
            ?.maxOrNull()
            ?: 0

    internal fun debugActiveCommandJobCount(session: DefaultWebSocketServerSession): Int =
        connections[session]?.activeCommandJobs?.size ?: 0

    internal fun debugOutboundMailboxSnapshot(session: DefaultWebSocketServerSession): OutboundMailboxSnapshot? =
        connections[session]?.outboundMailbox?.snapshot()

    internal fun debugConnectionCount(): Int = connections.size
}

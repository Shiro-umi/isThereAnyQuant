package org.shiroumi.agent.impl

import com.agentclientprotocol.common.Event
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import model.ws.AgentInterruptionReason
import org.shiroumi.agent.api.AgentBridge
import org.shiroumi.agent.acp.AcpClient
import org.shiroumi.agent.state.ClaudeState
import org.shiroumi.agent.state.ClaudeUpdate
import org.shiroumi.agent.state.StateManager
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class AgentBridgeImpl : AgentBridge {

    companion object {
        /** 事件流空闲超时（最近一次 ACP event 之后多久无新事件视为卡住）。
         *  买卖点分析等深度任务可能涉及多周期扫描+多工具调用，
         *  模型在等待工具返回后进行深度推理时可能长时间不产事件。 */
        private const val IDLE_TIMEOUT_MS = 180_000L
        /** watchdog 轮询周期，控制空闲检测的粒度。 */
        private const val WATCHDOG_TICK_MS = 5_000L
    }

    private val defaultStateManager = StateManager()
    private val acpClient = AcpClient(defaultStateManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStateManagers = ConcurrentHashMap<String, StateManager>()
    private val sessionPromptJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var isLaunched = false
    private var launchConfig: AgentBridge.Config? = null
    private var defaultSessionId: String? = null

    /** 追踪当前正在处理的 prompt 事件流 Job，用于 interrupt 后等待其完成 */
    @Volatile
    private var currentPromptJob: Job? = null

    override suspend fun launch(config: AgentBridge.Config) {
        check(!isLaunched) { "Agent already launched" }

        logger.info { "[AgentBridge] ▶ launch workDir=${config.workDir} isolated=${config.isolated} apiKey=${if (config.apiKey.isBlank()) "ENV" else "config"}" }

        try {
            launchConfig = config
            acpClient.initialize(
                AcpClient.Config(
                    claudeCommand = config.claudeCommand,
                    workDir = config.workDir,
                    isolated = config.isolated,
                    backtestMode = config.backtestMode,
                    sandboxTier = config.sandboxTier,
                    preferZedAcpAgent = config.preferZedAcpAgent,
                    apiKey = config.apiKey,
                    configDir = config.configDir,
                    modelId = config.modelId,
                    baseUrl = config.baseUrl,
                    provider = config.provider,
                    onProcessStarted = config.onProcessStarted,
                )
            )
            isLaunched = true
            logger.info { "[AgentBridge] ✔ launch complete; ACP process initialized" }

        } catch (e: Exception) {
            defaultStateManager.setError("Failed to launch agent: ${e.message}")
            logger.error(e) { "[AgentBridge] ✘ launch failed: ${e::class.simpleName}: ${e.message}" }
            acpClient.shutdown()
            throw e
        }
    }

    override suspend fun createSession(workDir: String?): String {
        check(isLaunched) { "Agent not launched" }
        val config = checkNotNull(launchConfig) { "Agent launch config missing" }
        val effectiveWorkDir = workDir ?: config.workDir
        val stateManager = StateManager()
        val sessionId = acpClient.newSession(effectiveWorkDir, stateManager)
        sessionStateManagers[sessionId] = stateManager
        if (defaultSessionId == null) {
            defaultSessionId = sessionId
        }
        logger.info { "[AgentBridge] ✔ ACP session created sessionId=$sessionId totalSessions=${sessionStateManagers.size}" }
        return sessionId
    }

    override suspend fun sendCommand(command: AgentBridge.Command) {
        val sessionId = defaultSessionId ?: createSession()
        sendCommand(sessionId, command)
    }

    override suspend fun sendCommand(sessionId: String, command: AgentBridge.Command) {
        check(isLaunched) { "Agent not launched" }

        when (command) {
            is AgentBridge.Command.Prompt -> handlePrompt(sessionId, command.message)
            is AgentBridge.Command.Approve -> stateManagerFor(sessionId).approve(command.requestId)
            is AgentBridge.Command.Reject -> stateManagerFor(sessionId).reject(command.requestId)
            is AgentBridge.Command.Interrupt -> interrupt(sessionId)
        }
    }

    private suspend fun interrupt(
        sessionId: String,
        reason: AgentInterruptionReason = AgentInterruptionReason.USER_CANCELLED
    ) {
        logger.info { "[AgentBridge] interrupt() requested sessionId=$sessionId reason=$reason" }
        // 取消 Kotlin 侧的 event 流协程，释放 ACP SDK 内部锁
        sessionPromptJobs[sessionId]?.let { job ->
            if (job.isActive) {
                logger.info { "[AgentBridge] cancelling currentPromptJob sessionId=$sessionId" }
                job.cancel()
            }
        }
        scope.launch {
            runCatching { acpClient.cancel(sessionId) }
                .onFailure { e ->
                    logger.warn(e) { "[AgentBridge] ACP cancel failed for sessionId=$sessionId, falling back to SIGINT" }
                    acpClient.interrupt()
                }
        }
        // 通知 StateManager 中断，让等待中的权限请求快速返回；状态增量投递可能因慢 socket 背压挂起，
        // 所以底层 ACP cancel 必须先发出，避免用户点击 Stop 后模型仍继续跑。
        stateManagerFor(sessionId).interrupt(reason)
        logger.info { "[AgentBridge] interrupt() dispatched sessionId=$sessionId reason=$reason" }
    }

    private suspend fun handlePrompt(sessionId: String, message: String) {
        val stateManager = stateManagerFor(sessionId)

        logger.info { "[AgentBridge] ▶ handlePrompt sessionId=$sessionId msg(${message.length}chars)=${message.take(80).replace("\n", "↵")}${if (message.length > 80) "…" else ""}" }

        // 等待上一个 Job 完成（cancel 后协程退出需要一小段时间）
        val prevJob = sessionPromptJobs[sessionId]
        if (prevJob != null && prevJob.isActive) {
            logger.info { "[AgentBridge] ⏳ waiting for previous prompt job to finish sessionId=$sessionId" }
            val done = withTimeoutOrNull(3000L) { prevJob.join() }
            if (done == null) {
                logger.warn { "[AgentBridge] ⚠ previous job still alive after 3s, proceeding anyway" }
            }
        }

        stateManager.reset()

        try {
            logger.info { "[AgentBridge] ▶ about to call acpClient.prompt()..." }
            val events: Flow<Event> = acpClient.prompt(sessionId, message)
            logger.info { "[AgentBridge] event flow received, processing…" }
            val job = stateManager.processEventFlow(events, sessionId)
            sessionPromptJobs[sessionId] = job
            currentPromptJob = job

            // watchdog：每 WATCHDOG_TICK_MS 检查一次距离最近一帧 event 的时间，
            // 超过 IDLE_TIMEOUT_MS 视为模型/进程卡住，触发 interrupt 并标记 IDLE_TIMEOUT。
            val watchdog = scope.launch {
                while (isActive && job.isActive) {
                    delay(WATCHDOG_TICK_MS)
                    val last = stateManager.lastEventTimestamp
                    if (last <= 0L) continue
                    val idleMs = System.currentTimeMillis() - last
                    if (idleMs >= IDLE_TIMEOUT_MS) {
                        logger.warn { "[AgentBridge] ⚠ idle timeout sessionId=$sessionId idle=${idleMs}ms" }
                        interrupt(sessionId, AgentInterruptionReason.IDLE_TIMEOUT)
                        stateManager.markIdleTimeout(idleMs)
                        break
                    }
                }
            }

            try {
                logger.info { "[AgentBridge] ⏳ waiting for event flow to complete (idle timeout=${IDLE_TIMEOUT_MS}ms)..." }
                job.join()
                logger.info { "[AgentBridge] ✔ prompt flow completed sessionId=$sessionId" }
            } finally {
                watchdog.cancel()
            }
        } catch (e: Exception) {
            stateManager.setProcessError("Prompt execution failed: ${e.message ?: e::class.simpleName}")
            logger.error(e) { "[AgentBridge] ✘ prompt failed sessionId=$sessionId: ${e::class.simpleName}: ${e.message}" }
            throw e
        } finally {
            sessionPromptJobs.remove(sessionId)
            if (currentPromptJob?.isActive != true) {
                currentPromptJob = null
            }
        }
    }

    override fun observeState(): StateFlow<ClaudeState> {
        val sessionId = defaultSessionId
        return if (sessionId != null) observeState(sessionId) else defaultStateManager.state
    }

    override fun observeState(sessionId: String): StateFlow<ClaudeState> = stateManagerFor(sessionId).state

    override fun observeUpdates(sessionId: String): SharedFlow<ClaudeUpdate> =
        stateManagerFor(sessionId).updates

    /**
     * 检查底层 ACP 运行时是否健康。
     * 健康定义：进程存活 + 协议已初始化。
     */
    override fun isHealthy(): Boolean = acpClient.isHealthy()

    override fun closeSession(sessionId: String) {
        logger.info { "[AgentBridge] closeSession sessionId=$sessionId" }
        sessionPromptJobs.remove(sessionId)?.cancel()
        sessionStateManagers.remove(sessionId)?.dispose()
        acpClient.forgetSession(sessionId)
        if (defaultSessionId == sessionId) {
            defaultSessionId = sessionStateManagers.keys().asSequence().firstOrNull()
        }
    }

    override fun shutdown() {
        logger.info { "Shutting down AgentBridge..." }
        currentPromptJob?.cancel()
        sessionPromptJobs.values.forEach { it.cancel() }
        sessionPromptJobs.clear()
        isLaunched = false
        sessionStateManagers.values.forEach { it.dispose() }
        sessionStateManagers.clear()
        defaultStateManager.dispose()
        acpClient.shutdown()
        scope.cancel()
        logger.info { "AgentBridge shutdown complete" }
    }

    private fun stateManagerFor(sessionId: String): StateManager =
        sessionStateManagers[sessionId] ?: error("ACP session $sessionId is not registered")
}

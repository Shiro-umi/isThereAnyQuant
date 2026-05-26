package org.shiroumi.agent.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.shiroumi.agent.state.PermissionResult
import org.shiroumi.agent.state.StateManager
import org.shiroumi.agent.util.ProcessTreeUtil
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/** 读取当前 InputStream 中已经可用的字节（非阻塞），不等待流关闭 */
private fun InputStream.readAvailable(): String {
    val available = available()
    if (available <= 0) return ""
    val buf = ByteArray(available)
    val read = read(buf, 0, available)
    return if (read > 0) buf.copyOf(read).toString(Charsets.UTF_8) else ""
}


/**
 * ACP SDK 客户端封装
 * 负责管理与 Claude Code 进程的 stdio 通信
 */
private object OpenRouterConfig {
    const val FREE_MODEL_TIMEOUT_MS = 600000L // 10分钟
    const val PAID_MODEL_TIMEOUT_MS = 120000L // 2分钟
    
    fun isFreeModel(modelId: String): Boolean {
        return modelId.contains(":free", ignoreCase = true)
    }
}

class AcpClient(
    private var stateManager: StateManager? = null
) {
    private data class ManagedSession(
        val session: ClientSession,
        val operations: QuantClientSessionOperations
    )

    private var scope: CoroutineScope? = null
    private var protocol: Protocol? = null
    private var client: Client? = null
    private var currentSession: ClientSession? = null
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ManagedSession>()
    private var process: Process? = null
    private var clientOperations: QuantClientSessionOperations? = null
    private var config: Config? = null

    companion object {
        private const val CLAUDE_SUBDIR = ".claude"
        private const val CLAUDE_BIN = "claude"
        private const val ACP_AGENT_BIN = "claude-agent-acp"

        private fun isCommandAvailable(command: String): Boolean {
            return try {
                val checkProcess = ProcessBuilder("which", command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                checkProcess.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        fun getDefaultClaudeCommand(workDir: String): String {
            if (isCommandAvailable(ACP_AGENT_BIN)) {
                logger.info { "Using @zed-industries/claude-agent-acp from system PATH" }
                return ACP_AGENT_BIN
            }
            val projectClaudePath = java.io.File(workDir, "$CLAUDE_SUBDIR/$CLAUDE_BIN").absolutePath
            if (java.io.File(projectClaudePath).exists()) {
                logger.info { "Using project-specific Claude: $projectClaudePath" }
                return projectClaudePath
            }
            logger.info { "Using system Claude as fallback" }
            return CLAUDE_BIN
        }
    }


    /**
     * @param claudeCommand Claude 可执行文件路径，null 时自动检测
     * @param workDir 工作目录，不存在时自动创建
     * @param isolated 隔离全局 skill/MCP，只加载 workDir/.claude/ 下的配置
     * @param preferZedAcpAgent 是否优先使用 @zed-industries/claude-agent-acp
     * @param onProcessStarted 进程启动后的回调，传入 pid
     */
    data class Config(
        val claudeCommand: String? = null,
        val workDir: String = System.getProperty("user.dir"),
        val isolated: Boolean = true,
        val preferZedAcpAgent: Boolean = true,
        val apiKey: String = "",
        val configDir: String? = null,
        val modelId: String? = null,
        val baseUrl: String? = null,
        val provider: String = "anthropic",
        val onProcessStarted: ((pid: Long) -> Unit)? = null,
    )

    suspend fun initialize(config: Config) {
        logger.info { "[AcpClient] ▶ initialize workDir=${config.workDir} isolated=${config.isolated} preferZedAcpAgent=${config.preferZedAcpAgent}" }

        this.config = config

        val claudeCmd = config.claudeCommand ?: run {
            if (config.preferZedAcpAgent && isCommandAvailable(ACP_AGENT_BIN)) {
                logger.info { "[AcpClient] ✔ auto-detected $ACP_AGENT_BIN" }
                ACP_AGENT_BIN
            } else {
                getDefaultClaudeCommand(config.workDir)
            }
        }

        logger.info { "[AcpClient] using claudeCommand=$claudeCmd" }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val workDir = java.io.File(config.workDir).also { it.mkdirs() }
        val args = buildList {
            add(claudeCmd)
            add("--dangerously-skip-permissions")
            if (config.isolated) {
                add("--setting-sources")
                add("project")
                add("--mcp-config")
                add("{\"mcpServers\":{}}")
                add("--strict-mcp-config")
            }
        }
        logger.info { "[AcpClient] process args=${args.joinToString(" ")}" }
        val processBuilder = ProcessBuilder(args)
            .directory(workDir)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        if (config.isolated) {
            val resolvedConfigDir = config.configDir?.let { java.io.File(it) }
                ?: workDir.resolve(".claude-isolated")
            resolvedConfigDir.mkdirs()
            processBuilder.environment()["CLAUDE_CONFIG_DIR"] = resolvedConfigDir.absolutePath
            logger.info { "[AcpClient] 🛡 Isolated mode active, CLAUDE_CONFIG_DIR=${resolvedConfigDir.absolutePath}" }
        }

        injectAuthEnv(processBuilder, config)

        process = withContext(Dispatchers.IO) { processBuilder.start() }
        val input: InputStream = process!!.inputStream
        val output: OutputStream = process!!.outputStream

        val pid = process!!.pid()

        logger.info { "[AcpClient] ✔ process started pid=$pid" }

        // 触发回调，让 ktor 侧注册到 registry
        config.onProcessStarted?.invoke(pid)

        val transport = StdioTransport(
            parentScope = scope!!,
            ioDispatcher = Dispatchers.IO,
            input = input.asSource().buffered(),
            output = output.asSink().buffered(),
            name = "quant-client"
        )

        val newProtocol = Protocol(scope!!, transport)
        protocol = newProtocol

        val newClient = Client(newProtocol)
        client = newClient

        newProtocol.start()
        logger.info { "[AcpClient] protocol started" }

        val clientInfo = ClientInfo(
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                terminal = true
            ),
            implementation = Implementation(name = "quant-agent", version = "1.0.0")
        )

        logger.info { "[AcpClient] ▶ client.initialize name=${clientInfo.implementation?.name} caps=fs,terminal" }
        val agentInfo = newClient.initialize(clientInfo)
        logger.info { "[AcpClient] ✔ client initialized agentName=${agentInfo.implementation?.name} agentVersion=${agentInfo.implementation?.version}" }
    }

    suspend fun newSession(cwd: String, stateManager: StateManager? = null): String {
        val client = checkNotNull(client) { "Client not initialized. Call initialize() first." }

        logger.info { "[AcpClient] ▶ newSession cwd=$cwd" }

        val effectiveStateManager = stateManager ?: this.stateManager
        val operations = QuantClientSessionOperations(
            autoApproveTools = true,
            stateManager = effectiveStateManager,
            workDir = config?.workDir ?: cwd,
        )
        clientOperations = operations

        val session = client.newSession(
            SessionCreationParameters(cwd, emptyList())
        ) { _, _ -> operations }

        sessions[session.sessionId.value] = ManagedSession(session, operations)
        currentSession = session
        logger.info { "[AcpClient] ✔ session created sessionId=${session.sessionId.value}" }

        return session.sessionId.value
    }

    suspend fun prompt(sessionId: String, message: String): Flow<Event> {
        val session = sessions[sessionId]?.session
            ?: currentSession?.takeIf { it.sessionId.value == sessionId }
            ?: error("Session $sessionId not found. Call newSession() first.")

        logger.info { "[AcpClient] ▶ prompt sessionId=$sessionId msg(${message.length}chars)=${message.take(80).replace("\n", "↵")}${if (message.length > 80) "…" else ""}" }
        logger.debug { "[AcpClient] Full message: $message" }

        return try {
            session.prompt(listOf(ContentBlock.Text(message)))
        } catch (e: Exception) {
            logger.error(e) { "[AcpClient] ✘ session.prompt() threw exception: ${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    fun getCurrentSessionId(): String? = currentSession?.sessionId?.value

    fun isInitialized(): Boolean = client != null && protocol != null

    /**
     * 检查 ACP 客户端整体是否健康。
     * - 进程必须存活
     * - 协议和客户端必须已初始化
     * 注意：无法检测协议状态机卡死的情况（需配合 createSession 超时机制兜底）。
     */
    fun isHealthy(): Boolean {
        return isInitialized() && process?.isAlive == true
    }

    fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    suspend fun cancel(sessionId: String) {
        val session = sessions[sessionId]?.session
            ?: currentSession?.takeIf { it.sessionId.value == sessionId }
            ?: run {
                logger.warn { "[AcpClient] cancel() called for unknown sessionId=$sessionId" }
                return
            }
        logger.info { "[AcpClient] Sending ACP session/cancel sessionId=$sessionId" }
        session.cancel()
    }

    fun forgetSession(sessionId: String) {
        sessions.remove(sessionId)
        if (currentSession?.sessionId?.value == sessionId) {
            currentSession = null
        }
        logger.info { "[AcpClient] Forgot local ACP session binding sessionId=$sessionId remaining=${sessions.size}" }
    }

    /**
     * 向底层 Claude 进程发送 SIGINT 信号，中断当前任务。
     * 进程本身不会退出，可在中断后继续接受新的 Prompt。
     */
    fun interrupt() {
        val p = process
        if (p == null) {
            logger.warn { "[AcpClient] interrupt() called but no process is running" }
            return
        }
        try {
            // ProcessHandle.of() + destroy() 会发 SIGTERM，
            // 直接通过 ProcessBuilder(signal) 发 SIGINT 才能让交互式 REPL 优雅暂停
            val pid = p.pid()
            logger.info { "[AcpClient] Sending SIGINT to process pid=$pid" }
            Runtime.getRuntime().exec(arrayOf("kill", "-INT", pid.toString()))
            logger.info { "[AcpClient] SIGINT sent to pid=$pid" }
        } catch (e: Exception) {
            logger.error(e) { "[AcpClient] Failed to send SIGINT: ${e.message}" }
        }
    }

    fun shutdown() {
        logger.info { "[AcpClient] Shutting down..." }

        currentSession = null
        sessions.clear()
        clientOperations = null
        config = null

        try {
            protocol?.close()
            logger.info { "[AcpClient] Protocol closed" }
        } catch (e: Exception) {
            logger.error(e) { "[AcpClient] Error closing protocol" }
        }

        // 递归杀掉整个进程树
        val p = process
        if (p != null && p.isAlive) {
            val pid = p.pid()
            val tree = ProcessTreeUtil.collectProcessTree(pid)
            logger.info { "[AcpClient] Terminating process tree: ${tree.joinToString(",")} (${tree.size} process(es))" }

            // 第一轮：SIGTERM
            tree.forEach { treePid ->
                try {
                    Runtime.getRuntime().exec(arrayOf("kill", "-TERM", treePid.toString()))
                } catch (e: Exception) {
                    logger.error(e) { "[AcpClient] Failed to send SIGTERM to pid=$treePid" }
                }
            }

            // 等待 5 秒（短轮询，避免阻塞过久）
            val deadline = System.currentTimeMillis() + 5000
            var terminated = false
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    terminated = true
                    break
                }
                Thread.sleep(100)
            }

            // 第二轮：SIGKILL 仍存活的进程
            if (!terminated && p.isAlive) {
                logger.warn { "[AcpClient] Process still alive after grace period, sending SIGKILL" }
                tree.forEach { treePid ->
                    val stillAlive = ProcessHandle.of(treePid).map { it.isAlive }.orElse(false)
                    if (stillAlive == true) {
                        try {
                            Runtime.getRuntime().exec(arrayOf("kill", "-KILL", treePid.toString()))
                        } catch (e: Exception) {
                            logger.error(e) { "[AcpClient] Failed to send SIGKILL to pid=$treePid" }
                        }
                    }
                }
            }

            logger.info { "[AcpClient] Process tree terminated" }
        }

        scope?.cancel()
        logger.info { "[AcpClient] Scope cancelled" }

        protocol = null
        client = null
        process = null
        scope = null

        logger.info { "[AcpClient] Shutdown complete" }
    }
}

/**
 * ACP 客户端会话操作实现
 * 处理权限请求、文件系统操作和终端操作
 */
class QuantClientSessionOperations(
    private val autoApproveTools: Boolean = false,
    private val stateManager: StateManager? = null,
    workDir: String = System.getProperty("user.dir"),
) : ClientSessionOperations, FileSystemOperations, TerminalOperations {

    private val sanitizer = org.shiroumi.agent.security.PathSanitizer(workDir)
    private val workspaceRoot = java.io.File(workDir).toPath().toAbsolutePath().normalize()
    private val activeTerminals = java.util.concurrent.ConcurrentHashMap<String, Process>()
    /** 进程退出后缓存的完整输出，terminalOutput 会优先返回这里的内容 */
    private val completedOutputs = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: kotlinx.serialization.json.JsonElement?
    ): RequestPermissionResponse {
        val toolName = toolCall.title ?: "Unknown Tool"
        val options = permissions.map { it.optionId.value }
        logger.info { "[QuantOps] ▶ requestPermissions tool=$toolName toolCallId=${toolCall.toolCallId} options=$options" }

        // 回填真实命令到 StateManager 的 log 条目中
        stateManager?.updateToolCallInput(toolCall.toolCallId.value, toolName)

        if (autoApproveTools && permissions.isNotEmpty()) {
            logger.info { "[QuantOps] ✔ auto-approved tool=$toolName" }
            return RequestPermissionResponse(
                RequestPermissionOutcome.Selected(permissions.first().optionId),
                _meta
            )
        }

        val stateMgr = stateManager ?: run {
            logger.warn { "[QuantOps] ⚠ no StateManager, auto-approving tool=$toolName" }
            val firstOptionId = permissions.firstOrNull()?.optionId
            return RequestPermissionResponse(
                if (firstOptionId != null) RequestPermissionOutcome.Selected(firstOptionId) else RequestPermissionOutcome.Cancelled,
                _meta
            )
        }

        val requestId = "${toolCall.toolCallId.value}_${System.currentTimeMillis()}"
        val availableOptions = permissions.map { it.optionId.value }

        logger.info { "[QuantOps] ⏳ awaiting user approval tool=$toolName requestId=$requestId options=$availableOptions" }

        val result = stateMgr.requestUserPermission(
            requestId = requestId,
            toolCallId = toolCall.toolCallId.value,
            toolName = toolName,
            description = "Tool execution request: $toolName",
            availableOptions = availableOptions
        )

        return when (result) {
            is PermissionResult.Approved -> {
                logger.info { "[QuantOps] ✔ permission APPROVED tool=$toolName requestId=$requestId optionId=${result.optionId}" }
                val selectedOption = permissions.find { it.optionId.value == result.optionId }
                    ?: permissions.firstOrNull()
                RequestPermissionResponse(
                    if (selectedOption != null) RequestPermissionOutcome.Selected(selectedOption.optionId) else RequestPermissionOutcome.Cancelled,
                    _meta
                )
            }
            is PermissionResult.Rejected -> {
                logger.info { "[QuantOps] ✘ permission REJECTED tool=$toolName requestId=$requestId" }
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled, _meta)
            }
            is PermissionResult.Cancelled -> {
                logger.info { "[QuantOps] ✘ permission CANCELLED (timeout?) tool=$toolName requestId=$requestId" }
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled, _meta)
            }
        }
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: kotlinx.serialization.json.JsonElement?
    ) {
        logger.debug { "Received notification: ${notification::class.simpleName}" }
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: kotlinx.serialization.json.JsonElement?
    ): ReadTextFileResponse {
        logger.info { "[QuantOps] 📂 fsRead path=$path line=$line limit=$limit" }
        val text = resolveWorkspacePath(path).readText()
        logger.info { "[QuantOps] ✔ fsRead path=$path size=${text.length}chars" }
        return ReadTextFileResponse(sanitizer.sanitize(text))
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: kotlinx.serialization.json.JsonElement?
    ): WriteTextFileResponse {
        logger.info { "[QuantOps] ✏ fsWrite path=$path size=${content.length}chars" }
        resolveWorkspacePath(path).writeText(content)
        logger.info { "[QuantOps] ✔ fsWrite done path=$path" }
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: kotlinx.serialization.json.JsonElement?
    ): CreateTerminalResponse {
        logger.info { "[QuantOps] 💻 terminalCreate command=${command.take(200)} args=${args.take(20)} cwd=${cwd ?: "inherit"} envCount=${env.size}" }
        if (args.isNotEmpty()) {
            args.forEachIndexed { i, a -> logger.info { "[QuantOps]   arg[$i]=${a.take(200)}" } }
        }

        val fullCmdString = if (command == "sh" && args.firstOrNull() == "-c") {
            args.getOrNull(1) ?: ""
        } else {
            (listOf(command) + args).joinToString(" ")
        }

        val validation = org.shiroumi.agent.security.CommandWhitelist.validate(fullCmdString)
        if (validation is org.shiroumi.agent.security.CommandWhitelist.Result.Denied) {
            logger.warn { "[QuantOps] ⛔ BLOCKED command: ${fullCmdString.take(200)} reason=${validation.reason}" }
            return createDeniedTerminal(validation.reason)
        }

        val shellArgs = if (command == "sh" && args.firstOrNull() == "-c") {
            listOf(command) + args
        } else {
            listOf("sh", "-c", fullCmdString)
        }

        logger.info { "[QuantOps] 🔧 executing via shell: ${shellArgs.take(3).joinToString(" | argv=")}" }
        if (shellArgs.size > 2) {
            logger.info { "[QuantOps]   shell cmd: ${shellArgs.last().take(300)}" }
        }

        val processBuilder = ProcessBuilder(shellArgs)
        processBuilder.directory(cwd?.let { resolveWorkspacePath(it) } ?: workspaceRoot.toFile())
        env.forEach { processBuilder.environment()[it.name] = it.value }
        processBuilder.redirectErrorStream(false)  // 分离 stderr 以便单独读取
        val process = processBuilder.start()
        val terminalId = java.util.UUID.randomUUID().toString()
        activeTerminals[terminalId] = process
        logger.info { "[QuantOps] ✔ terminalCreate terminalId=$terminalId pid=${process.pid()}" }
        return CreateTerminalResponse(terminalId)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?
    ): TerminalOutputResponse {
        // 如果进程已退出，返回缓存的完整输出
        completedOutputs[terminalId]?.let { cached ->
            logger.info { "[QuantOps] 📤 terminalOutput terminalId=$terminalId (from cache) len=${cached.length}" }
            return TerminalOutputResponse(cached, truncated = false)
        }
        // 进程还在运行，非阻塞读取当前可用的内容
        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        val stdout = process.inputStream.readAvailable()
        val stderr = process.errorStream.readAvailable()
        val output = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) { if (isNotEmpty()) appendLine(); append("STDERR:\n"); append(stderr) }
        }
        val sanitized = sanitizer.sanitize(output)
        logger.info { "[QuantOps] 📤 terminalOutput terminalId=$terminalId (live) stdout=${stdout.length}chars stderr=${stderr.length}chars" }
        if (sanitized.isNotEmpty()) logger.info { "[QuantOps]   preview: ${sanitized.take(200).replace("\n", "↵")}..." }
        return TerminalOutputResponse(sanitized, truncated = false)
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?
    ): ReleaseTerminalResponse {
        activeTerminals.remove(terminalId)
        completedOutputs.remove(terminalId)
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?
    ): WaitForTerminalExitResponse {
        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        logger.info { "[QuantOps] ⏳ terminalWaitForExit terminalId=$terminalId" }
        // 并行读取 stdout/stderr，防止缓冲区满而锁死
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }
        val exitCode = process.waitFor()
        val stdout = stdoutFuture.get()
        val stderr = stderrFuture.get()

        val fullOutput = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) { if (isNotEmpty()) appendLine(); append("STDERR:\n"); append(stderr) }
        }
        // 缓存完整输出，供后续 terminalOutput 调用返回
        completedOutputs[terminalId] = sanitizer.sanitize(fullOutput)
        logger.info { "[QuantOps] ✔ terminalExited terminalId=$terminalId exitCode=$exitCode stdoutLen=${stdout.length} stderrLen=${stderr.length}" }
        if (stdout.isNotEmpty()) logger.info { "[QuantOps]   stdout: ${stdout.take(400).replace("\n", "↵")}" }
        if (stderr.isNotEmpty()) logger.warn { "[QuantOps]   stderr: ${stderr.take(400).replace("\n", "↵")}" }
        return WaitForTerminalExitResponse(exitCode.toUInt())
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?
    ): KillTerminalCommandResponse {
        activeTerminals[terminalId]?.destroy()
        return KillTerminalCommandResponse()
    }

    private fun createDeniedTerminal(reason: String): CreateTerminalResponse {
        val terminalId = java.util.UUID.randomUUID().toString()
        completedOutputs[terminalId] = "ERROR: 命令不允许。$reason\n允许的命令: get-candles, get-intraday-candles, get-research-reports, get-industry-research-reports, get-limit-list, market-emotion, echo ... | bc"
        return CreateTerminalResponse(terminalId)
    }

    private fun resolveWorkspacePath(path: String): java.io.File {
        val requested = java.nio.file.Path.of(path)
        val resolved = if (requested.isAbsolute) {
            requested.normalize()
        } else {
            workspaceRoot.resolve(requested).normalize()
        }
        if (!resolved.startsWith(workspaceRoot)) {
            error("Path outside current work directory is not allowed: $path")
        }
        return resolved.toFile()
    }
}

/**
 * 从 ~/.claude/settings.json 读取 env 配置并注入到子进程
 * 支持 ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN 及其相关配置
 */
private fun injectAuthEnv(processBuilder: ProcessBuilder, config: AcpClient.Config) {
    val homeDir = System.getProperty("user.home")
    val settingsFile = java.io.File(homeDir, ".claude/settings.json")
    val configApiKey = config.apiKey

    val claudeEnv = if (settingsFile.exists()) {
        try {
            val content = settingsFile.readText()
            val json = kotlinx.serialization.json.Json.parseToJsonElement(content)
            json.jsonObject["env"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
        } catch (e: Exception) {
            logger.warn { "[AcpClient] Failed to parse ~/.claude/settings.json: ${e.message}" }
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
    } else {
        kotlinx.serialization.json.JsonObject(emptyMap())
    }

    val env = processBuilder.environment()

    val provider = config.provider.lowercase()
    val usesAuthToken = provider != "anthropic" ||
        config.baseUrl?.contains("openrouter.ai") == true ||
        configApiKey.startsWith("sk-or-")

    // 优先使用 config 中的 apiKey
    if (configApiKey.isNotBlank()) {
        if (usesAuthToken) {
            // 非 Anthropic 官方端点通常通过 AUTH_TOKEN 走 Claude Code 兼容鉴权
            env["ANTHROPIC_AUTH_TOKEN"] = configApiKey
            env["ANTHROPIC_API_KEY"] = ""
            if (provider == "openrouter" || config.baseUrl?.contains("openrouter.ai") == true) {
                env["HTTP-Referer"] = "https://github.com/Shiro-umi/isThereAnyQuant"
                env["X-Title"] = "Quant-Agent"
            }
            logger.info { "[AcpClient] 🔑 custom provider detected ($provider) — setting ANTHROPIC_AUTH_TOKEN and clearing ANTHROPIC_API_KEY" }
        
            // 对于免费模型给出警告
            val modelId = config.modelId
            if (modelId != null && OpenRouterConfig.isFreeModel(modelId)) {
                logger.warn { "[AcpClient] ⚠ OpenRouter free model detected ($modelId), expect longer response times" }
            }
        } else {
            env["ANTHROPIC_API_KEY"] = configApiKey
            logger.info { "[AcpClient] 🔑 ANTHROPIC_API_KEY source=config fingerprint=${apiKeyFingerprint(configApiKey)}" }
        }
    } else {
        // 从 settings.json 或系统环境继承
        when {
            claudeEnv["ANTHROPIC_AUTH_TOKEN"] != null -> {
                val token = claudeEnv["ANTHROPIC_AUTH_TOKEN"]!!.jsonPrimitive.content
                env["ANTHROPIC_AUTH_TOKEN"] = token
                logger.info { "[AcpClient] 🔑 ANTHROPIC_AUTH_TOKEN source=~/.claude/settings.json fingerprint=${apiKeyFingerprint(token)}" }
            }
            claudeEnv["ANTHROPIC_API_KEY"] != null -> {
                val key = claudeEnv["ANTHROPIC_API_KEY"]!!.jsonPrimitive.content
                env["ANTHROPIC_API_KEY"] = key
                logger.info { "[AcpClient] 🔑 ANTHROPIC_API_KEY source=~/.claude/settings.json fingerprint=${apiKeyFingerprint(key)}" }
            }
            !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank() -> {
                val key = System.getenv("ANTHROPIC_API_KEY")!!
                env["ANTHROPIC_API_KEY"] = key
                logger.info { "[AcpClient] 🔑 ANTHROPIC_API_KEY source=env fingerprint=${apiKeyFingerprint(key)}" }
            }
            else -> {
                logger.warn { "[AcpClient] ⚠ No ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN found — claude process may fail to authenticate" }
            }
        }
    }

    // 注入其他相关的 Claude Code 环境变量
    val modelRelatedKeys = listOf(
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "ANTHROPIC_REASONING_MODEL"
    )
    
    val otherKeys = listOf(
        "CLAUDE_CODE_ATTRIBUTION_HEADER",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "CLAUDE_CODE_ENABLE_TELEMETRY"
    )

    // 如果处于隔离模式，跳过从全局 settings.json 继承模型相关的路径/型号配置
    val keysToInject = if (config.isolated) otherKeys else (otherKeys + modelRelatedKeys)

    for (key in keysToInject) {
        claudeEnv[key]?.let {
            val value = it.jsonPrimitive.content
            env[key] = value
            logger.debug { "[AcpClient] 📝 $key=$value (from global settings)" }
        }
    }

    // 如果配置中显式指定了 URL 或模型，则覆盖（这比从全局 settings.json 继承更高优先级）
    config.baseUrl?.let {
        env["ANTHROPIC_BASE_URL"] = it
        logger.info { "[AcpClient] 🌍 ANTHROPIC_BASE_URL override: $it" }
    }
    
    config.modelId?.let {
        env["ANTHROPIC_MODEL"] = it
        // 同时设置所有默认型号，确保全面覆盖
        env["ANTHROPIC_DEFAULT_OPUS_MODEL"] = it
        env["ANTHROPIC_DEFAULT_SONNET_MODEL"] = it
        env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = it
        env["ANTHROPIC_REASONING_MODEL"] = it
        logger.info { "[AcpClient] 🤖 ANTHROPIC_MODEL override: $it" }
    }
}

private fun apiKeyFingerprint(apiKey: String): String {
    if (apiKey.isBlank()) return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

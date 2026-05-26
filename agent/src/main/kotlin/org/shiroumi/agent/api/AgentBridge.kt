package org.shiroumi.agent.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.shiroumi.agent.state.ClaudeState
import org.shiroumi.agent.state.ClaudeUpdate

/**
 * AgentBridge - 对外接口
 *
 * 作为调用方与 Claude Code 之间的桥梁，提供：
 * - 启动/关闭 Agent
 * - 发送命令（Prompt、批准、拒绝）
 * - 观察状态变化
 *
 * 使用示例：
 * ```kotlin
 * val bridge = AgentBridgeImpl()
 * bridge.launch(AgentBridge.Config(workDir = "/path/to/project"))
 * bridge.sendCommand(AgentBridge.Command.Prompt("列出当前目录的文件"))
 * bridge.observeState().collect { state -> ... }
 * bridge.shutdown()
 * ```
 */
interface AgentBridge {

    suspend fun launch(config: Config)

    suspend fun createSession(workDir: String? = null): String

    suspend fun sendCommand(command: Command)

    suspend fun sendCommand(sessionId: String, command: Command)

    fun observeState(): StateFlow<ClaudeState>

    fun observeState(sessionId: String): StateFlow<ClaudeState>

    /**
     * 观察 [sessionId] 会话的细粒度增量事件流。
     *
     * 与 [observeState] 的关键区别：
     * - [observeState] 是 [StateFlow]，下游处理一帧时新值会覆盖旧值，公网慢链路下中间帧必然丢失
     * - [observeUpdates] 是 [SharedFlow]，每个 ACP chunk/工具事件都会作为离散 [ClaudeUpdate] 投递
     *
     * 调用方应当：
     * 1. 先用 [observeState] 取当前快照下发 SYNC
     * 2. 紧接着 collect [observeUpdates] 把后续增量翻译为 wire 协议的 Delta
     */
    fun observeUpdates(sessionId: String): SharedFlow<ClaudeUpdate>

    fun closeSession(sessionId: String)

    fun shutdown()

    /**
     * 检查底层 ACP 运行时是否健康。
     * 健康定义：进程存活 + 协议已初始化。
     */
    fun isHealthy(): Boolean

    /**
     * 启动配置
     *
     * @param workDir 工作目录，不存在时自动创建
     * @param claudeCommand Claude 可执行文件路径，null 时自动检测
     * @param isolated 是否隔离全局 skill/MCP，只加载 workDir/.claude/ 下的配置，默认 true
     * @param preferZedAcpAgent 是否优先使用 @zed-industries/claude-agent-acp，默认 true
     */
    data class Config(
        val workDir: String = System.getProperty("user.dir"),
        val claudeCommand: String? = null,
        val isolated: Boolean = true,
        val preferZedAcpAgent: Boolean = true,
        /** 注入到 claude 进程的 ANTHROPIC_API_KEY，空字符串时不覆盖环境变量 */
        val apiKey: String = "",
        /** 隔离环境的配置目录路径 */
        val configDir: String? = null,
        /** 自定义模型 ID */
        val modelId: String? = null,
        /** 自定义 API Base URL */
        val baseUrl: String? = null,
        /** 模型供应商，用于决定 Claude Code 鉴权环境变量注入策略 */
        val provider: String = "anthropic",
        /** 进程启动后的回调，传入 pid */
        val onProcessStarted: ((pid: Long) -> Unit)? = null,
    )

    sealed class Command {
        data class Prompt(val message: String) : Command()
        data class Approve(val requestId: String) : Command()
        data class Reject(val requestId: String) : Command()
        /** 中断当前正在执行的任务（向底层进程发送 SIGINT） */
        data object Interrupt : Command()
    }
}

package org.shiroumi.server.runtime.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import utils.logger

/**
 * 通用型 Server 生命周期管理器。
 *
 * 这是新的 server 级基础设施，负责统一管理：
 * - 启动阶段
 * - 关键 warmup
 * - READY 门禁
 * - 生命周期失败后的退化状态
 *
 * 关键设计原则：
 * 1. 不能再把 warmup 绑定到 websocket 订阅路径
 * 2. 生命周期必须是通用设施，而不是 DataProvider 专用启动器
 * 3. 是否允许接受订阅，只能由这里统一决定
 */
class ServerLifecycleManager(
    private val tasks: List<LifecycleTask>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : LifecycleGate {
    private val logger by logger("ServerLifecycleManager")
    private val started = AtomicBoolean(false)

    private val _phaseFlow = MutableStateFlow(ServerLifecyclePhase.BOOTSTRAPPING)
    override val phaseFlow: StateFlow<ServerLifecyclePhase> = _phaseFlow.asStateFlow()

    /**
     * 幂等启动生命周期管理流程。
     *
     * 启动顺序固定为：
     * 1. 进入 BOOTSTRAPPING
     * 2. 按阶段执行 LifecycleTask
     * 3. 基础设施启动完成后进入 READY
     * 4. 业务订阅门禁就绪后进入 SUBSCRIPTION_READY
     * 5. 任一关键任务失败则进入 DEGRADED
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            val phases = listOf(
                ServerLifecyclePhase.BOOTSTRAPPING,
                ServerLifecyclePhase.WARMING_UP
            )

            for (phase in phases) {
                _phaseFlow.value = phase
                logger.info(">>> 进入阶段: $phase")
                val phaseStart = System.currentTimeMillis()
                val phaseTasks = tasks.filter { it.phase == phase }
                for (task in phaseTasks) {
                    logger.info("  开始任务: ${task.name}")
                    val taskStart = System.currentTimeMillis()
                    val result = runCatching { task.execute() }
                    val taskElapsed = System.currentTimeMillis() - taskStart
                    result.onSuccess {
                        logger.info("  完成任务: ${task.name} (${taskElapsed}ms)")
                    }.onFailure { error ->
                        logger.error("  任务失败: ${task.name} (${taskElapsed}ms), error=${error.message}")
                        if (task.critical) {
                            _phaseFlow.value = ServerLifecyclePhase.DEGRADED
                            return@launch
                        }
                    }
                }
                val phaseElapsed = System.currentTimeMillis() - phaseStart
                logger.info("<<< 阶段完成: $phase (${phaseElapsed}ms)")
            }

            _phaseFlow.value = ServerLifecyclePhase.READY
            logger.info("Server lifecycle entered READY")
            _phaseFlow.value = ServerLifecyclePhase.SUBSCRIPTION_READY
            logger.info("Server lifecycle entered SUBSCRIPTION_READY")
        }
    }

    /**
     * 主动进入关闭态。
     *
     * 当前阶段先提供显式状态切换，
     * 具体的关闭编排后续再补。
     */
    fun shutdown() {
        _phaseFlow.value = ServerLifecyclePhase.SHUTTING_DOWN
    }

    override fun canAcceptBusinessSubscriptions(): Boolean =
        phaseFlow.value == ServerLifecyclePhase.SUBSCRIPTION_READY

    override fun canActivateProviders(): Boolean = when (phaseFlow.value) {
        ServerLifecyclePhase.BOOTSTRAPPING,
        ServerLifecyclePhase.WARMING_UP,
        ServerLifecyclePhase.READY,
        ServerLifecyclePhase.SUBSCRIPTION_READY -> true
        ServerLifecyclePhase.DEGRADED,
        ServerLifecyclePhase.SHUTTING_DOWN -> false
    }

    override fun canRunHistoricalUpdate(): Boolean = canActivateProviders()
}

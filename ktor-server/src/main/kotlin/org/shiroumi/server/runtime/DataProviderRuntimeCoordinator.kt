package org.shiroumi.server.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import org.shiroumi.server.dataprovider.registry.DataProviderRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DataProvider 运行时协调器。
 *
 * 它把 `ExecutionPhaseService` 的阶段变化翻译成对全部 Provider 的刷新指令。
 * 这样所有 Provider 都只关心：
 * - 当前阶段是什么
 * - 因为什么原因触发刷新
 *
 * 而不需要各自再监听时钟或市场状态。
 */
class DataProviderRuntimeCoordinator(
    private val executionPhaseService: ExecutionPhaseService,
    private val registry: DataProviderRegistry,
    /**
     * 允许在测试中注入可控 scope，避免把 collector 固定绑死在 `Dispatchers.Default`。
     *
     * 这样测试可以把协调器放进 `runTest` 的调度体系里，确保初始 phase 发射、
     * collector 执行和断言使用的是同一套虚拟时间语义，而不是靠 `delay()` 猜测时机。
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val started = AtomicBoolean(false)

    /**
     * 启动运行时协调逻辑。
     * 一旦阶段流发生变化，就广播到 Registry。
     *
     * 这里同样要求幂等，避免重复挂载 collector。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        executionPhaseService.phaseFlow
            .onEach { phase ->
                registry.notifyPhaseChanged(phase, phase.toDefaultCause())
            }
            .launchIn(scope)
        executionPhaseService.tradingTickFlow
            .onEach {
                registry.notifyTradingTick(UpdateCause.TRADING_TICK)
            }
            .launchIn(scope)
    }

    /**
     * 将阶段语义映射为默认更新原因。
     * 这样日志和后续观测里能看出本次刷新是由哪类调度触发。
     */
    private fun ExecutionPhase.toDefaultCause(): UpdateCause = when (this) {
        ExecutionPhase.OFF_MARKET -> UpdateCause.OFF_MARKET_SYNC
        ExecutionPhase.TRADING_ACTIVE -> UpdateCause.TRADING_TICK
        ExecutionPhase.TRADING_BREAK -> UpdateCause.TRADING_BREAK_RECALIBRATE
    }

    /**
     * 测试或进程关闭时可以主动释放内部 collector。
     */
    fun stop() {
        scope.cancel()
    }
}

package org.shiroumi.server.dataprovider.registry

import model.dataprovider.DataProviderKey
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import org.shiroumi.server.dataprovider.contract.DataProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * DataProvider 注册中心。
 *
 * 它是运行时对所有 Provider 实例的统一管理入口。
 *
 * 当前职责：
 * - 注册 Provider
 * - 注销 Provider
 * - 按 Key 查找 Provider
 * - 将执行阶段变化广播给全部 Provider
 *
 * 当前刻意保持简单，不引入复杂生命周期管理；
 * 等到 Provider 实例真正开始按业务动态创建时，再继续扩展。
 */
class DataProviderRegistry {
    private val providers = ConcurrentHashMap<String, DataProvider<*, *>>()

    /**
     * 注册 Provider。
     * 使用 `key.id` 作为基础设施层索引键，便于统一管理。
     */
    fun register(provider: DataProvider<*, *>) {
        providers[provider.key.id] = provider
    }

    /**
     * 按 key 注销 Provider。
     *
     * 这里不是简单地把注册表项删除掉，而是要联动触发 Provider 自身的释放逻辑。
     * 这样才能确保：
     * 1. Registry 索引被移除
     * 2. Provider 持有的快照槽位被显式释放
     *
     * 如果只删注册项不释放快照，就会留下失去入口但仍占内存的残留状态。
     */
    suspend fun unregister(key: DataProviderKey) {
        providers.remove(key.id)?.release()
    }

    /**
     * 按强类型 key 查找 Provider。
     * 返回值仍是统一抽象，具体类型由上层服务自行做安全转换。
     */
    fun find(key: DataProviderKey): DataProvider<*, *>? = providers[key.id]

    /**
     * 返回当前所有已注册 Provider。
     * 主要用于运行时调度与调试观察。
     */
    fun all(): List<DataProvider<*, *>> = providers.values.toList()

    /**
     * 把阶段变化广播给所有 Provider。
     * 当前采用串行调用，优先保证语义清晰；
     * 后续如有性能压力，再评估并发分发。
     */
    suspend fun notifyPhaseChanged(phase: ExecutionPhase, cause: UpdateCause) {
        providers.values.forEach { provider ->
            provider.onPhaseChanged(phase, cause)
        }
    }

    /**
     * 只向支持盘中实时窗口的 Provider 广播一次交易 tick。
     *
     * 这条通道不能复用 `notifyPhaseChanged`，否则会把 OFF_MARKET / TRADING_BREAK
     * 的阶段语义错误地变成周期性历史刷新。
     */
    suspend fun notifyTradingTick(cause: UpdateCause) {
        providers.values.forEach { provider ->
            if (provider.supportsRealtimeTicks()) {
                provider.refreshRealtime(cause)
            }
        }
    }
}

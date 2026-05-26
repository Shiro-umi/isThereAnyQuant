package org.shiroumi.server.dataprovider.service

import model.dataprovider.DataProviderKey
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import org.shiroumi.server.dataprovider.contract.DataProvider
import org.shiroumi.server.dataprovider.registry.DataProviderRegistry

/**
 * DataProvider 写服务。
 *
 * 目标：
 * - 统一承载“刷新哪个 Provider、为什么刷新”的调用入口
 * - 避免调用方直接操作具体 Provider 实现
 * - 为后续轮询器、调度器、运行时协调器提供稳定 API
 *
 * 重要约束：
 * - 它是通用更新服务，不承担值类型强类型封装职责
 * - 真正的领域更新能力应通过更上层的应用服务或 facade 表达
 */
class DataProviderUpdateService(
    private val registry: DataProviderRegistry
) {
    /**
     * 按 key 查找 Provider 抽象实例。
     */
    fun findProvider(key: DataProviderKey): DataProvider<*, *>? =
        registry.find(key)

    /**
     * 刷新历史窗口 H。
     */
    suspend fun refreshHistorical(key: DataProviderKey, cause: UpdateCause) {
        findProvider(key)?.refreshHistorical(cause)
    }

    /**
     * 刷新实时窗口 R。
     */
    suspend fun refreshRealtime(key: DataProviderKey, cause: UpdateCause) {
        findProvider(key)?.refreshRealtime(cause)
    }

    /**
     * 执行午休或单次校准逻辑。
     */
    suspend fun recalibrate(key: DataProviderKey, cause: UpdateCause) {
        findProvider(key)?.recalibrate(cause)
    }

    /**
     * 按执行阶段刷新所有 Provider。
     * 当前直接委托给 Registry 统一广播。
     */
    suspend fun refreshAllForPhase(phase: ExecutionPhase, cause: UpdateCause) {
        registry.notifyPhaseChanged(phase, cause)
    }
}

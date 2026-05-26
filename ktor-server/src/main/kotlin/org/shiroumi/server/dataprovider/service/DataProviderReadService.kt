package org.shiroumi.server.dataprovider.service

import kotlinx.coroutines.flow.StateFlow
import model.dataprovider.DataProviderKey
import model.dataprovider.DataProviderSnapshot
import org.shiroumi.server.dataprovider.contract.DataProvider
import org.shiroumi.server.dataprovider.registry.DataProviderRegistry

/**
 * DataProvider 读服务。
 *
 * 目标：
 * - 给 HTTP / WebSocket / 其他应用服务提供统一读取入口
 * - 隔离调用方对 Registry 和 Provider 细节的直接依赖
 *
 * 当前阶段保持最薄：
 * - 查找 Provider
 * - 读取当前快照
 * - 订阅快照流
 *
 * 重要约束：
 * - 它是通用读服务，不提供伪装出来的值类型强类型安全
 * - 领域层若需要强类型 API，应在此之上封装各自的 facade
 */
class DataProviderReadService(
    private val registry: DataProviderRegistry
) {
    /**
     * 按 key 查找 Provider 抽象实例。
     * 此处有意返回星号类型，避免伪装成值类型完全安全的泛型 API。
     */
    fun findProvider(key: DataProviderKey): DataProvider<*, *>? =
        registry.find(key)

    /**
     * 同步读取当前快照。
     */
    fun readSnapshot(key: DataProviderKey): DataProviderSnapshot<*>? =
        findProvider(key)?.currentSnapshot()

    /**
     * 订阅快照流。
     * 后续 Projection 层会优先基于这个入口工作。
     */
    fun observeSnapshot(key: DataProviderKey): StateFlow<DataProviderSnapshot<*>?>? =
        findProvider(key)?.snapshotFlow
}

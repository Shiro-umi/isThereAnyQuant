package org.shiroumi.server.dataprovider.store

import model.dataprovider.DataProviderSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于内存的 SnapshotStore 实现。
 *
 * 适用场景：
 * - 当前阶段的抽象层落地
 * - 单进程服务端运行时缓存
 * - 后续所有 Provider 的统一快照承载
 *
 * 当前实现重点：
 * 1. 使用普通 Map 承载每个 key 的最新快照
 * 2. Provider 生命周期结束时可真实删除对应 key
 * 3. 订阅流由 Provider 自身持有，Store 不承担订阅源角色
 *
 * 说明：
 * - 这里不做隐式淘汰
 * - 回收由 Provider release 显式触发
 * - Store 不保留已释放 key 的空槽位
 */
class InMemorySnapshotStore<T> : SnapshotStore<T> {
    private val snapshots = ConcurrentHashMap<String, DataProviderSnapshot<T>>()

    /**
     * 同步读取快照。
     */
    override fun get(keyId: String): DataProviderSnapshot<T>? = snapshots[keyId]

    /**
     * 写入新快照。
     *
     * 关键逻辑：
     * - 覆盖写入最新快照
     * - 不创建额外订阅槽位
     */
    override suspend fun put(snapshot: DataProviderSnapshot<T>) {
        snapshots[snapshot.key.id] = snapshot
    }

    /**
     * 删除指定 key 当前承载的快照值。
     * Provider release 后，Store 不再保留空槽位。
     */
    override suspend fun remove(keyId: String) {
        snapshots.remove(keyId)
    }

    /**
     * 当前缓存中的 key 数量。
     * 主要用于测试与调试。
     */
    fun size(): Int = snapshots.size
}

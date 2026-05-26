package org.shiroumi.server.dataprovider.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import model.dataprovider.DataProviderKey
import model.dataprovider.DataProviderSnapshot
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import org.shiroumi.server.dataprovider.contract.DataProvider
import org.shiroumi.server.dataprovider.store.SnapshotStore

/**
 * DataProvider 抽象基类。
 *
 * 目的：
 * - 收敛所有 Provider 共有的阶段切换逻辑
 * - 收敛快照发布逻辑
 * - 提供统一的 `nextSnapshot` 构造方式
 *
 * 子类只需要关注：
 * - 如何拉取/计算历史数据
 * - 如何拉取/计算实时数据
 * - 如何在自己的领域内组织 merged 视图
 */
abstract class AbstractDataProvider<K : DataProviderKey, T>(
    final override val key: K,
    private val snapshotStore: SnapshotStore<T>
) : DataProvider<K, T> {

    /**
     * 基类统一维护 Provider 生命周期内的快照流。
     * Store 只保存当前值，不再承担订阅源角色。
     */
    private val mutableSnapshotFlow = MutableStateFlow(snapshotStore.get(key.id))

    final override val snapshotFlow: StateFlow<DataProviderSnapshot<T>?> = mutableSnapshotFlow

    /**
     * 基类统一实现阶段切换分发。
     *
     * 这是当前抽象层最重要的约束之一：
     * - `OFF_MARKET` 走历史刷新
     * - `TRADING_ACTIVE` 走实时刷新
     * - `TRADING_BREAK` 走单次校准
     */
    final override suspend fun onPhaseChanged(phase: ExecutionPhase, cause: UpdateCause) {
        when (phase) {
            ExecutionPhase.OFF_MARKET -> refreshHistorical(cause)
            ExecutionPhase.TRADING_ACTIVE -> refreshRealtime(cause)
            ExecutionPhase.TRADING_BREAK -> recalibrate(cause)
        }
    }

    /**
     * 基类统一实现资源释放逻辑。
     *
     * 这里当前只做一件事：显式删除自身 key 对应的快照槽位。
     * 这样可以保证 Provider 的生命周期在抽象层内部闭环，
     * 而不是把 Store 的细节暴露给 Registry 或更外层调用方。
     */
    final override suspend fun release() {
        mutableSnapshotFlow.value = null
        snapshotStore.remove(key.id)
    }

    /**
     * 将构造好的新快照写入统一存储。
     * 所有 Provider 的最终写入都应该经过这里，避免旁路修改。
     */
    protected suspend fun publishSnapshot(snapshot: DataProviderSnapshot<T>) {
        snapshotStore.put(snapshot)
        mutableSnapshotFlow.value = snapshot
    }

    /**
     * 生成下一版快照。
     *
     * 关键约束：
     * - `version` 自动递增
     * - `merged` 由 Provider 在写入前计算完成
     * - 读侧不再负责合并 H / R
     */
    protected fun nextSnapshot(
        phase: ExecutionPhase,
        historical: List<T>,
        realtime: List<T>,
        merged: List<T>,
        updatedAt: Long = System.currentTimeMillis()
    ): DataProviderSnapshot<T> {
        val currentVersion = mutableSnapshotFlow.value?.version ?: 0L
        return DataProviderSnapshot(
            key = key,
            phase = phase,
            historical = historical,
            realtime = realtime,
            merged = merged,
            version = currentVersion + 1,
            updatedAt = updatedAt
        )
    }

    /**
     * 在 realtime 拉取/计算失败时冻结当前业务值。
     *
     * 约束语义：
     * - 若当前已有成功快照，则保持 `historical / realtime / merged` 不变
     * - 仅当阶段发生变化时，允许用相同业务值推进 `phase`
     * - 若当前尚无快照，则可退回到调用方提供的历史兜底值
     */
    protected suspend fun publishFrozenSnapshot(
        phase: ExecutionPhase,
        historicalFallback: List<T> = emptyList()
    ) {
        val current = currentSnapshot()
        when {
            current != null && current.phase != phase -> {
                publishSnapshot(
                    nextSnapshot(
                        phase = phase,
                        historical = current.historical,
                        realtime = current.realtime,
                        merged = current.merged
                    )
                )
            }

            current != null -> Unit

            historicalFallback.isNotEmpty() -> {
                publishSnapshot(
                    nextSnapshot(
                        phase = phase,
                        historical = historicalFallback,
                        realtime = emptyList(),
                        merged = historicalFallback
                    )
                )
            }
        }
    }
}

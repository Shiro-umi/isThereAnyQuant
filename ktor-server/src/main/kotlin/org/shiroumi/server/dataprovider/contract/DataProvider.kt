package org.shiroumi.server.dataprovider.contract

import kotlinx.coroutines.flow.StateFlow
import model.dataprovider.DataProviderKey
import model.dataprovider.DataProviderSnapshot
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause

/**
 * DataProvider 核心抽象接口。
 *
 * 它描述的是“一个可维护快照、可响应阶段切换、可对外提供读取/订阅”的数据供应器，
 * 而不是单纯的远端接口封装或数据库 Repository。
 *
 * 职责边界：
 * - 负责维护本领域数据的 H / R / merged 快照
 * - 负责响应执行阶段切换
 * - 负责暴露当前快照与快照流
 * - 负责在生命周期结束时释放自己占用的快照槽位
 *
 * 非职责：
 * - 不直接管理 WebSocket session
 * - 不直接管理 HTTP 路由
 * - 不负责连接远端接口的底层细节实现
 */
interface DataProvider<K : DataProviderKey, T> {
    /**
     * Provider 的强类型主键。
     * 它是这个 Provider 在整个系统中的唯一领域标识。
     */
    val key: K

    /**
     * Provider 当前快照流。
     * 外部只读，不允许越过 Provider 直接写入。
     */
    val snapshotFlow: StateFlow<DataProviderSnapshot<T>?>

    /**
     * 获取当前快照的便捷方法。
     * 对读侧来说，这通常是最常用的同步入口。
     */
    fun currentSnapshot(): DataProviderSnapshot<T>? = snapshotFlow.value

    /**
     * 是否参与交易时段的周期性 realtime tick。
     *
     * 默认关闭，避免把“阶段切换语义”和“盘中心跳语义”混在一起。
     * 只有真正维护盘中 R 轨道的 Provider 才应返回 `true`。
     */
    fun supportsRealtimeTicks(): Boolean = false

    /**
     * 当系统执行阶段发生变化时的统一入口。
     *
     * 例如：
     * - 开盘 -> 切换到实时刷新策略
     * - 午休 -> 执行单次校准
     * - 收盘 -> 回到历史同步策略
     */
    suspend fun onPhaseChanged(phase: ExecutionPhase, cause: UpdateCause)

    /**
     * 刷新历史窗口 H。
     * 一般用于非交易时段的历史同步与落库之后的内存刷新。
     */
    suspend fun refreshHistorical(cause: UpdateCause)

    /**
     * 刷新实时窗口 R。
     * 一般用于交易时段，从远端实时接口拉取后覆盖写入。
     */
    suspend fun refreshRealtime(cause: UpdateCause)

    /**
     * 午休或特殊场景下的单次校准入口。
     * 语义上与高频实时刷新不同，因此单独抽出来。
     */
    suspend fun recalibrate(cause: UpdateCause)

    /**
     * 释放 Provider 占用的运行时资源。
     *
     * 当前阶段最关键的资源是快照槽位。
     * 之所以把释放语义放进 Provider 契约，而不是交给外部直接碰 Store，
     * 是为了保证“谁创建并维护了快照语义，谁负责完整结束它”。
     *
     * 这样 Registry 在注销 Provider 时，就能通过统一入口完成：
     * 1. 注销运行时索引
     * 2. 释放对应快照槽位
     */
    suspend fun release()
}

package model.dataprovider

import kotlinx.serialization.Serializable

/**
 * 内存快照存储策略。
 *
 * 该策略不直接决定业务语义，而是约束基础设施层的缓存行为：
 * - `maxEntries`：最多保留多少个快照槽位
 * - `expireAfterAccessMillis`：按访问时间淘汰
 * - `expireAfterWriteMillis`：按写入时间淘汰
 *
 * 说明：
 * - 当前阶段只实现了最基础的内存淘汰。
 * - 后续可以根据实际业务容量引入更严格的 LRU / 分层缓存策略。
 */
@Serializable
data class SnapshotStorePolicy(
    val maxEntries: Int = 1024,
    val expireAfterAccessMillis: Long = 60 * 60 * 1000L,
    val expireAfterWriteMillis: Long = 24 * 60 * 60 * 1000L
)

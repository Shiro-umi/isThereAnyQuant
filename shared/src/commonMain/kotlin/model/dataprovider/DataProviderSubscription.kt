package model.dataprovider

import kotlinx.serialization.Serializable

/**
 * DataProvider 订阅描述模型。
 *
 * 当前阶段它主要是抽象层占位：
 * - `key` 表示订阅哪个 Provider
 * - `replayCurrentSnapshot` 表示首次订阅时是否立即回放当前快照
 *
 * 后续 WebSocket / Projection 层落地时，会进一步扩展该模型。
 */
@Serializable
data class DataProviderSubscription(
    val key: DataProviderKey,
    val replayCurrentSnapshot: Boolean = true
)

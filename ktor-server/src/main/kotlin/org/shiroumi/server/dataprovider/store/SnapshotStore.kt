package org.shiroumi.server.dataprovider.store

import model.dataprovider.DataProviderSnapshot

/**
 * DataProvider 快照存储接口。
 *
 * 这是新架构最核心的基础设施抽象之一。
 * 它只负责存取 Provider 快照，不负责：
 * - 业务计算
 * - session 管理
 * - remote 请求
 *
 * 设计要求：
 * - Store 只承担当前快照缓存，不承担订阅源角色
 * - 写操作只能接受完整快照，避免局部字段被绕过修改
 */
interface SnapshotStore<T> {
    fun get(keyId: String): DataProviderSnapshot<T>?

    suspend fun put(snapshot: DataProviderSnapshot<T>)

    suspend fun remove(keyId: String)
}

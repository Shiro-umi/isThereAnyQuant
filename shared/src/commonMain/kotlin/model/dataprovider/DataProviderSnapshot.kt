package model.dataprovider

/**
 * DataProvider 在服务端内部和共享领域层使用的统一快照模型。
 *
 * 语义约定：
 * - `historical`：历史常驻窗口 H，通常来自数据库或离线同步结果
 * - `realtime`：交易日内实时窗口 R，通常来自远端实时接口
 * - `merged`：提供给 HTTP / WS / 订阅者的统一消费视图
 *
 * 设计重点：
 * 1. 读侧不再自己拼接 H + R，而是直接读取 `merged`
 * 2. `version` 用于标识快照变更次数，便于订阅推送判定是否需要广播
 * 3. `updatedAt` 用于调试、监控、缓存观测
 *
 * 额外约束：
 * - 这里刻意不标记为 `@Serializable`
 * - 原因是快照里的值类型 `T` 属于领域内部类型，不应被误解为天然可直接透传给 HTTP / WS
 * - 真正的对外传输模型应由 Projection / DTO 层单独定义
 */
data class DataProviderSnapshot<T>(
    val key: DataProviderKey,
    val phase: ExecutionPhase,
    val historical: List<T> = emptyList(),
    val realtime: List<T> = emptyList(),
    val merged: List<T> = emptyList(),
    val version: Long = 0L,
    val updatedAt: Long = 0L
) {
    /**
     * 便捷判断当前快照是否包含实时窗口。
     *
     * 典型用途：
     * - 前端展示当前是否处于盘中实时态
     * - 投影层决定推送标签
     * - 调试时快速判断 H / R 是否都已就绪
     */
    fun hasRealtime(): Boolean = realtime.isNotEmpty()
}

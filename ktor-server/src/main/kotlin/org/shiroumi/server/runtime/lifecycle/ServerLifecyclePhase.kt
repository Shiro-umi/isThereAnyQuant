package org.shiroumi.server.runtime.lifecycle

/**
 * Server 级统一生命周期阶段。
 *
 * 这里建模的是“整个服务当前是否已经具备对外提供业务订阅能力”，
 * 它与 `ExecutionPhaseService` 的交易时段语义不是一回事：
 *
 * - `ExecutionPhaseService` 只回答“现在是盘中、午休还是盘后”
 * - `ServerLifecyclePhase` 回答“服务是否已经完成关键 warmup，能否安全接受订阅”
 *
 * 之所以单独引入这套状态机，是因为：
 * 1. Provider 初始化、预热、状态恢复必须与 server 生命周期绑定
 * 2. 订阅路径不能再隐式承担 warmup 职责
 * 3. 外部订阅必须在系统 READY 之后才允许建立
 */
enum class ServerLifecyclePhase {
    /**
     * 基础设施正在装配。
     *
     * 此时 registry、snapshot store、runtime 等对象可能刚完成创建，
     * 但关键 warmup 任务还没有开始。
     */
    BOOTSTRAPPING,

    /**
     * 关键 warmup / preload / restore 任务执行中。
     *
     * 在该阶段：
     * - 允许后台 warmup 任务执行（catalog/last-known/data layer 等）
     * - 不允许对外接受业务订阅
     */
    WARMING_UP,

    /**
     * 关键启动任务已完成。
     *
     * 只有进入这个阶段，外部 websocket 业务订阅才允许建立。
     */
    READY,

    /**
     * 基础设施与关键启动门禁都已经完成，可以正式接受业务订阅。
     *
     * 之所以单独拆出这一阶段，是为了避免把“基础设施已启动”误当成
     * “所有订阅前置条件都已满足”。
     */
    SUBSCRIPTION_READY,

    /**
     * 服务处于可运行但已退化的状态。
     *
     * 典型场景：
     * - 关键 warmup 失败
     * - 关键依赖在运行中失效
     *
     * 当前阶段先用于表达“不要伪装成 READY”，
     * 后续再扩展更细的恢复或降级策略。
     */
    DEGRADED,

    /**
     * 关闭中。
     *
     * 此时不再接受新的业务订阅或新的激活任务。
     */
    SHUTTING_DOWN
}

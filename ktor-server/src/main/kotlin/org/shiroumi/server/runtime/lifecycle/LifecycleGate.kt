package org.shiroumi.server.runtime.lifecycle

import kotlinx.coroutines.flow.StateFlow

/**
 * Server 生命周期门禁。
 *
 * 这个对象把内部生命周期状态机收口成外部可消费的“是否允许做某件事”。
 * 这样 websocket、activation、批处理等入口都不需要自己散落判断 READY 与否。
 */
interface LifecycleGate {
    /**
     * 生命周期阶段流。
     *
     * 主要用于需要观察 server readiness 的组件，例如后续的监控或调试页面。
     */
    val phaseFlow: StateFlow<ServerLifecyclePhase>

    /**
     * 获取当前阶段。
     */
    fun currentPhase(): ServerLifecyclePhase = phaseFlow.value

    /**
     * 是否允许接受对外业务订阅。
     *
     * 当前规则固定为：只有 READY 才允许。
     */
    fun canAcceptBusinessSubscriptions(): Boolean

    /**
     * 是否允许 Provider 激活。
     *
     * 当前规则：
     * - BOOTSTRAPPING / WARMING_UP / READY 可以
     * - DEGRADED / SHUTTING_DOWN 不允许
     *
     * 这样既支持启动期 warmup，也支持 READY 之后的后台按需激活。
     */
    fun canActivateProviders(): Boolean

    /**
     * 是否允许执行盘后批处理。
     *
     * 当前阶段复用与 provider activation 一致的门禁规则，
     * 后续如果需要更细控制，再单独拆分。
     */
    fun canRunHistoricalUpdate(): Boolean
}

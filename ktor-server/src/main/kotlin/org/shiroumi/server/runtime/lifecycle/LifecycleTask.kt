package org.shiroumi.server.runtime.lifecycle

/**
 * Server 生命周期任务抽象。
 *
 * 它的目标不是只服务 DataProvider，而是给整个 server 提供一套通用的
 * “生命周期阶段 -> 任务集合 -> 执行结果” 描述方式。
 *
 * 设计要点：
 * 1. 任务必须可命名，便于日志、监控和排障
 * 2. 任务必须标记是否关键，决定失败是否阻断 READY
 * 3. 任务必须显式声明所属生命周期阶段，避免以后把不同阶段的初始化逻辑混在一起
 */
interface LifecycleTask {
    /**
     * 任务名。
     *
     * 要求稳定、可读，便于启动日志中直接定位。
     */
    val name: String

    /**
     * 是否关键任务。
     *
     * - `true`：失败会阻止系统进入 READY
     * - `false`：失败只记录日志，不阻断整体启动
     */
    val critical: Boolean

    /**
     * 任务应在哪个 server 生命周期阶段执行。
     */
    val phase: ServerLifecyclePhase

    /**
     * 执行任务。
     */
    suspend fun execute()
}

/**
 * 基于 lambda 的简单生命周期任务实现。
 *
 * 当前阶段主要用于 bootstrap 装配时快速声明任务，
 * 后续如果某类任务需要自己的状态或超时策略，再演进成专门实现类。
 */
class SimpleLifecycleTask(
    override val name: String,
    override val critical: Boolean,
    override val phase: ServerLifecyclePhase,
    private val block: suspend () -> Unit
) : LifecycleTask {
    override suspend fun execute() = block()
}

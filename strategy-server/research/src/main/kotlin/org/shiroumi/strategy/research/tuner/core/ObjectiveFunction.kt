package org.shiroumi.strategy.research.tuner.core

import org.shiroumi.strategy.research.pipeline.ResearchContext

/**
 * 黑盒优化的目标函数接口。
 *
 * 调优器只关心"参数 → 观测"这个最小接口；具体如何用 [params] 派生新 [ResearchContext]、
 * 调用哪条 [org.shiroumi.strategy.research.pipeline.ResearchPipeline]、从产物里抽出哪个标量，
 * 全部由 agent 在实现中决定。
 *
 * 这一层是黑盒优化器**唯一的研究侧耦合点**，确保 tuner 包不反向依赖任何具体 topic。
 */
fun interface ObjectiveFunction {

    /**
     * 在 [params] 下执行一次研究并返回观测。
     *
     * 实现方应该用 `ctx.copy(params = ctx.params + params)` 派生新上下文，
     * 再调用具体研究管线；调用过程中允许向 `ctx.workspace` 落中间态文件，但不要写数据库。
     */
    fun evaluate(ctx: ResearchContext, params: Map<String, String>): Observation
}

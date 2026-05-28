package org.shiroumi.strategy.research.tuner.core

import org.shiroumi.strategy.research.pipeline.ResearchContext

/**
 * 顶层优化器抽象。
 *
 * 任何调优器（黑盒/可微）都实现该接口，对外暴露统一的入口；内部状态、迭代语义、收敛判据
 * 由各实现自行决定，不强加共同基类。
 *
 * 设计原则：
 * - 调优器不感知任何具体研究 topic（情绪因子、周期函数 …）；研究侧通过传入的
 *   [run] lambda（黑盒优化器）或 [org.shiroumi.strategy.research.tuner.differentiable.DifferentiableModel]
 *   （可微优化器）注入目标语义。
 * - 调优器**不修改**传入的 [ResearchContext]，每次试探在内部派生新的参数视图。
 * - 调优器结果一律走 [TuningResult]，agent 通过统一格式读取最优参数。
 */
interface Optimizer {

    /** 调优器实现名（落盘到 [TuningResult.optimizerName]）。 */
    val name: String

    /** 调优预算。 */
    val budget: TuningBudget

    /**
     * 启动优化。具体形参由子接口约束。
     *
     * 由于黑盒与可微两类输入参数差异较大（一类是 `Map<String,String>` 与 SearchSpace，
     * 一类是 `DoubleArray` 与 DifferentiableModel），所以这里仅约束概念入口，
     * 具体 `run` / `optimize` 方法由各家自己声明。
     */
}

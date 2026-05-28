package org.shiroumi.strategy.research.tuner.differentiable

import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDManager

/**
 * 可微模型接口：用户提供一组**显式可微的数学公式**，调优器对其参数做反向梯度下降。
 *
 * 典型用法（情绪周期函数 / 股票评分函数）：
 * ```
 *   val model = object : DifferentiableModel {
 *       lateinit var A: NDArray; lateinit var f: NDArray; lateinit var phi: NDArray
 *
 *       override fun init(manager: NDManager): List<Parameter> {
 *           A   = manager.create(1.0f).also { it.setRequiresGradient(true) }
 *           f   = manager.create(0.05f).also { it.setRequiresGradient(true) }
 *           phi = manager.create(0.0f).also { it.setRequiresGradient(true) }
 *           return listOf(Parameter("A", A), Parameter("f", f), Parameter("phi", phi))
 *       }
 *
 *       override fun loss(manager: NDManager): NDArray {
 *           // y_hat = A * sin(2π·f·t + phi)
 *           val t = manager.arange(0f, 100f, 1f)
 *           val yHat = A.mul(t.mul(2 * Math.PI.toFloat()).mul(f).add(phi).sin())
 *           return yHat.sub(yObs).pow(2.0).mean()  // MSE loss
 *       }
 *   }
 * ```
 *
 * 设计原则：
 * - 模型自己持有 [NDArray] 参数引用（用 `lateinit var` 或 property），方便 forward 时取用
 * - 调优器**只**调用 [init] 一次、循环调用 [loss]，**不感知**参数语义
 * - 参数命名 [Parameter.name] 用于落盘到 [TrialRecord.params]，必须唯一
 *
 * **损失的方向**：[loss] 返回的标量将被**最小化**（梯度下降）。如果你的研究指标是越大越好
 * （如得分函数），在 [loss] 里返回 `-score`，并在 [Parameter] 中也按"待训练参数"理解。
 * tuner 内部最终落盘的 [Observation.score] 会取 `-loss`，恢复"越大越好"的统一语义。
 */
interface DifferentiableModel {

    /**
     * 初始化所有可训练参数。
     *
     * 实现方需要：
     * 1. 用 [manager] 创建 NDArray
     * 2. 对每个要训练的 NDArray 调 `setRequiresGradient(true)`
     * 3. 返回参数列表（顺序无关，但 name 必须唯一）
     *
     * 注意：所有 NDArray 必须由 [manager] 创建，调优器结束时会统一关闭。
     */
    fun init(manager: NDManager): List<Parameter>

    /**
     * 计算损失。每轮迭代调用一次，必须返回一个**标量** NDArray（shape=()）。
     *
     * 实现方可以借 [manager] 创建常量 / 临时张量（如真实标签 yObs），它们随梯度图自动清理。
     */
    fun loss(manager: NDManager): NDArray

    /**
     * 一个可训练参数。
     *
     * @property name  参数名（落盘用），全模型唯一
     * @property value NDArray 引用（需已 setRequiresGradient(true)，且形状任意——标量、向量、矩阵均可）
     */
    data class Parameter(val name: String, val value: NDArray)
}

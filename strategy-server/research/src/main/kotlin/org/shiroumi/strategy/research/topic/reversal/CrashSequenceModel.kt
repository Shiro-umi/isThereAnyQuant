package org.shiroumi.strategy.research.topic.reversal

import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.Shape
import org.shiroumi.strategy.research.tuner.differentiable.DifferentiableModel

/**
 * 大阴线杀跌预测的 **序列状态空间模型**（DJL 可微后端）—— 对用户洞察「任何状态 t 都可由之前窗口的状态递推得到」的忠实落地。
 *
 * 动机（2026-05-31）：静态 logistic `p_t=σ(w·x_{t−1}+b)` 把每天当独立样本、丢掉时序结构；而大阴线是一个**有时序的过程**
 * （脆弱积累 → 触发 → 崩盘）。前一轮证明 EWMA 线性记忆 s_t=λs_{t−1}+(1−λ)x_t 与已有滑动均共线、无增量——
 * 那是**固定衰减、线性**的记忆。本模型用**可学、非线性**的门控递归把它泛化：隐状态 h_t 由前 L 天非线性递推，
 * 记忆长度数据自适应（更新门 g 学），检验时序交互是否携带 logistic 吃不到的新信息。
 *
 * **最小门控循环单元（GRU 的更新门简化版，参数省、抗过拟合）**，对每个样本回看前 L 天子序列 x_{t−L+1..t}：
 *   g_l = σ(z_l · W_g)                      更新门（数据自适应记忆长度，= 可学的 1−λ）
 *   c_l = tanh(z_l · W_x)                   候选状态
 *   h_l = (1 − g_l) ⊙ h_{l−1} + g_l ⊙ c_l   状态递推（h_{−1}=0）
 *   p_t = σ(h_{L−1} · w_o + b_o)            读出
 *
 * **截断 BPTT + 滑动短窗（关键工程/统计选择）**：杀跌的时序依赖是短程的（数日~十数日），故每样本只回看 L=20 天，
 * 反传只穿过这 L 步。批量矩阵化为 X∈[n,L,k]，逐步 l 用一次 matmul 算全样本——无 Python 逐日循环，只 L 次张量步进，
 * 图小、快、且短窗 + 低隐维 d 是稀疏正类（14%）上的天然正则。
 *
 *   L = FocalBCE(α,γ) + λ·(‖W_x‖²+‖W_g‖²+‖w_o‖²)
 *
 * 泛化由 walk-forward OOS 验证；与静态 [CrashDifferentiableModel] 同口径对比 AUC / P70 / P80 / 深域 subAUC。
 */
class CrashSequenceModel(
    samples: List<PivotReversalFeatures.Sample>,
    private val featureKeys: List<String>,
    /** 回看窗 L（每样本的子序列长度）。 */
    private val lookback: Int = 20,
    /** 隐状态维度 d（小维度抗稀疏正类过拟合）。 */
    private val hidden: Int = 8,
    private val alpha: Double = 0.75,
    private val gammaFocal: Double = 1.5,
    private val l2: Double = 0.01,
) : DifferentiableModel {

    private val n = samples.size
    private val k = featureKeys.size
    private val L = lookback
    private val d = hidden
    private val alphaF = alpha.toFloat()
    private val gammaF = gammaFocal.toFloat()
    private val l2F = l2.toFloat()

    // 序列特征张量 X∈[n,L,k]：样本 i 的第 l 步 = samples[i−(L−1)+l] 的特征；窗口越界（i<L−1）用样本 i 自身填充（左 padding）。
    private val xFlat: FloatArray = FloatArray(n * L * k)
    private val yArr: FloatArray = FloatArray(n) { samples[it].label.toFloat() }

    init {
        for (i in 0 until n) {
            for (l in 0 until L) {
                val src = (i - (L - 1) + l).coerceAtLeast(0)   // 左 padding：早期窗口不足时重复最早可得样本
                val v = CrashDifferentiableModel.featureVector(samples[src], featureKeys)
                val base = (i * L + l) * k
                for (j in 0 until k) xFlat[base + j] = v[j].toFloat()
            }
        }
    }

    private lateinit var wx: NDArray   // [k,d]
    private lateinit var wg: NDArray   // [k,d]
    private lateinit var wo: NDArray   // [d]
    private lateinit var bo: NDArray   // 标量
    private lateinit var x: NDArray    // [n,L,k]
    private lateinit var y: NDArray    // [n]

    /** 最优 loss 对应的参数快照（JVM 堆，供 optimize 后打分）。 */
    private var bestWx = DoubleArray(k * d)
    private var bestWg = DoubleArray(k * d)
    private var bestWo = DoubleArray(d)
    private var bestBo = 0.0
    private var bestLoss = Double.POSITIVE_INFINITY

    override fun init(manager: NDManager): List<DifferentiableModel.Parameter> {
        x = manager.create(xFlat, Shape(n.toLong(), L.toLong(), k.toLong()))
        y = manager.create(yArr, Shape(n.toLong()))
        // 小随机初值（Xavier 量级）：序列模型零初值会让门恒为 0.5、梯度对称难破。用固定种子保证 walk-forward 可复现。
        val rnd = java.util.Random(42)
        val scale = (1.0 / kotlin.math.sqrt(k.toDouble())).toFloat()
        wx = manager.create(FloatArray(k * d) { (rnd.nextGaussian().toFloat()) * scale }, Shape(k.toLong(), d.toLong()))
            .also { it.setRequiresGradient(true) }
        wg = manager.create(FloatArray(k * d) { (rnd.nextGaussian().toFloat()) * scale }, Shape(k.toLong(), d.toLong()))
            .also { it.setRequiresGradient(true) }
        wo = manager.create(FloatArray(d) { (rnd.nextGaussian().toFloat()) * (1.0f / kotlin.math.sqrt(d.toDouble()).toFloat()) }, Shape(d.toLong()))
            .also { it.setRequiresGradient(true) }
        bo = manager.zeros(Shape()).also { it.setRequiresGradient(true) }
        return listOf(
            DifferentiableModel.Parameter("wx", wx),
            DifferentiableModel.Parameter("wg", wg),
            DifferentiableModel.Parameter("wo", wo),
            DifferentiableModel.Parameter("bo", bo),
        )
    }

    override fun loss(manager: NDManager): NDArray {
        val eps = 1e-7f
        // 隐状态 h∈[n,d]，从 0 起逐步递推 L 步。每步取 X[:,l,:]=[n,k]，一次 matmul 算全样本。
        var h: NDArray = manager.zeros(Shape(n.toLong(), d.toLong()))
        for (l in 0 until L) {
            val zl = x.get(":, $l, :")                    // [n,k]
            val g = sigmoid(zl.matMul(wg))                // [n,d] 更新门
            val c = zl.matMul(wx).tanh()                  // [n,d] 候选状态
            val oneMinusG = manager.create(1.0f).sub(g)
            h = oneMinusG.mul(h).add(g.mul(c))            // [n,d] 递推
        }
        val logit = h.matMul(wo).add(bo)                  // [n] 读出
        val p = sigmoid(logit).clip(eps, 1f - eps)
        val oneMinusP = manager.create(1.0f).sub(p)
        val oneMinusY = manager.create(1.0f).sub(y)
        val posTerm = y.mul(oneMinusP.pow(gammaF)).mul(p.log()).mul(alphaF)
        val negTerm = oneMinusY.mul(p.pow(gammaF)).mul(oneMinusP.log()).mul(1f - alphaF)
        val focal = posTerm.add(negTerm).mean().neg()
        val l2Term = wx.square().sum().add(wg.square().sum()).add(wo.square().sum()).mul(l2F)
        val lossArr = focal.add(l2Term)
        val lossVal = lossArr.toFloatArray()[0].toDouble()
        if (lossVal < bestLoss && lossVal.isFinite()) {
            bestLoss = lossVal
            bestWx = wx.toFloatArray().map { it.toDouble() }.toDoubleArray()
            bestWg = wg.toFloatArray().map { it.toDouble() }.toDoubleArray()
            bestWo = wo.toFloatArray().map { it.toDouble() }.toDoubleArray()
            bestBo = bo.toFloatArray()[0].toDouble()
        }
        return lossArr
    }

    /**
     * 用学到的参数对样本序列打分（JVM 端复算递推，与 loss 同公式）。
     * @param window 样本 t 的前 L 天子序列（升序，长度 L；调用方负责切片与左 padding）。
     */
    fun scoreOf(window: List<PivotReversalFeatures.Sample>): Double {
        val h = DoubleArray(d)
        for (l in 0 until L) {
            val s = window[l]
            val v = CrashDifferentiableModel.featureVector(s, featureKeys)
            for (a in 0 until d) {
                var gz = 0.0; var cz = 0.0
                for (j in 0 until k) { gz += v[j] * bestWg[j * d + a]; cz += v[j] * bestWx[j * d + a] }
                val g = 1.0 / (1.0 + kotlin.math.exp(-gz))
                val c = kotlin.math.tanh(cz)
                h[a] = (1.0 - g) * h[a] + g * c
            }
        }
        var logit = bestBo
        for (a in 0 until d) logit += h[a] * bestWo[a]
        return 1.0 / (1.0 + kotlin.math.exp(-logit))
    }

    private fun sigmoid(a: NDArray): NDArray = a.neg().exp().add(1.0).pow(-1.0)
}

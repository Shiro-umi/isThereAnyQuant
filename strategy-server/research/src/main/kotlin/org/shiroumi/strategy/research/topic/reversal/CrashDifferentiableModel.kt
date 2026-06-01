package org.shiroumi.strategy.research.topic.reversal

import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.Shape
import org.shiroumi.strategy.research.tuner.differentiable.DifferentiableModel

/**
 * 大阴线杀跌预测的 **L2 正则 logistic + 可学乘性软门控**（DJL 可微后端）。
 *
 * 框架动机：黑盒路径每轮加因子都遭遇「topK 机械截断好因子被挤 / NelderMead 28 维无导数搜索退化」，AUC 在 0.72–0.81 震荡。
 * 梯度 + **L2 正则**能**吃下全部因子而无需 topK 截断**：L2 自动把无用因子权重压向 0，梯度下降在高维远优于无导数搜索。
 *
 * **乘性软门控（2026-05-30，把「情绪域条件」从硬门控内生为公式可学项）**：
 * 之前「只在情绪极度转弱域 P^up<τ 预测」是模型**外部**的硬过滤 1[r_i<τ]——不可微、丢样本、把连续域压成 0/1。
 * 这里把它写进公式，作可微的乘性门：
 *
 *   logit_i = Σ_k w_k·x_{i,k} + b
 *   p_i = σ(logit_i) · σ( γ_g·(τ_g − r_i) )      r_i = trendScore_i（域划分器本体，越低越高危）
 *         └─ 择日：高危域内哪天跌 ─┘  └─ 择时：是否处于高危域（门）─┘
 *
 * τ_g（域阈值）、γ_g（门陡峭度，用 logGamma 参数化保 >0）作**可学参数**，在**全样本**上与 w/b 联合梯度训练，
 * 不丢任何样本。数学性质：γ_g→∞ 时 σ(γ_g(τ_g−r)) → 1[r<τ_g]，**软门控是硬门控的严格泛化**（容量不小于硬切，
 * 故 OOS 数学上不会更差）。这正是用户要的「特定条件变成公式的一部分」，且择时/择日两部分梯度可解耦归因（消融对比）。
 *
 *   L = FocalBCE(α 类别加权, γ 聚焦) + λ·‖w‖²
 *
 * 泛化由 walk-forward OOS 验证；λ、是否启用门由外层选。
 */
class CrashDifferentiableModel(
    samples: List<PivotReversalFeatures.Sample>,
    private val featureKeys: List<String>,
    private val alpha: Double = 0.75,
    private val gammaFocal: Double = 1.5,
    private val l2: Double = 0.01,
    /** 启用可学乘性软门控（择时项）。false 时退化为纯 logistic（消融基线）。 */
    private val useGate: Boolean = false,
    /** 门阈值 τ_g 初值（trendScore 刻度，~情绪转弱域上界）。 */
    private val gateTauInit: Double = 0.3,
    /** 门陡峭度 γ_g 初值（越大越接近硬门控）。 */
    private val gateGammaInit: Double = 10.0,
    /** loss 类型：FOCAL=FocalBCE（最大似然≈优化AUC）/ SOFT_FBETA=可微 Fβ（直接优化 P/R 工作点）。 */
    private val lossKind: LossKind = LossKind.FOCAL,
    /** Fβ 的 β：<1 偏精度（冲 P80），=1 平衡，>1 偏召回。 */
    private val fBeta: Double = 0.5,
) : DifferentiableModel {

    enum class LossKind { FOCAL, SOFT_FBETA }

    private val n = samples.size
    private val k = featureKeys.size
    // DJL 要求 Float32；Double 参数在此转为 Float 用于 NDArray 运算。
    private val alphaF = alpha.toFloat()
    private val gammaF = gammaFocal.toFloat()
    private val l2F = l2.toFloat()
    private val xFlat: FloatArray = FloatArray(n * k)
    private val yArr: FloatArray = FloatArray(n) { samples[it].label.toFloat() }
    // 门控输入 r_i = trendScore（域划分器本体），与样本同序；门控不进 logit 特征，仅作乘性结构。
    private val rArr: FloatArray = FloatArray(n) { samples[it].trendScore.toFloat() }

    init {
        for (i in 0 until n) {
            val v = featureVector(samples[i], featureKeys)
            for (j in 0 until k) xFlat[i * k + j] = v[j].toFloat()
        }
    }

    private lateinit var w: NDArray   // [k]
    private lateinit var b: NDArray   // 标量
    private lateinit var x: NDArray
    private lateinit var y: NDArray
    private lateinit var r: NDArray        // [n] 门控输入
    private lateinit var gateTau: NDArray  // 标量 τ_g
    private lateinit var gateLogGamma: NDArray  // 标量 log γ_g（exp 保正）

    /** 最优 loss 对应的权重快照（JVM 堆，供 optimize 后打分）。 */
    var bestWeights: DoubleArray = DoubleArray(k); private set
    var bestBias: Double = 0.0; private set
    /** 门控参数快照（useGate 时有效）：τ_g 与 γ_g。 */
    var bestGateTau: Double = gateTauInit; private set
    var bestGateGamma: Double = gateGammaInit; private set
    private var bestLoss: Double = Double.POSITIVE_INFINITY

    override fun init(manager: NDManager): List<DifferentiableModel.Parameter> {
        x = manager.create(xFlat, Shape(n.toLong(), k.toLong()))
        y = manager.create(yArr, Shape(n.toLong()))
        w = manager.zeros(Shape(k.toLong())).also { it.setRequiresGradient(true) }
        b = manager.zeros(Shape()).also { it.setRequiresGradient(true) }
        val params = mutableListOf(
            DifferentiableModel.Parameter("w", w),
            DifferentiableModel.Parameter("b", b),
        )
        if (useGate) {
            r = manager.create(rArr, Shape(n.toLong()))
            gateTau = manager.create(gateTauInit.toFloat()).also { it.setRequiresGradient(true) }
            gateLogGamma = manager.create(kotlin.math.ln(gateGammaInit).toFloat()).also { it.setRequiresGradient(true) }
            params += DifferentiableModel.Parameter("gateTau", gateTau)
            params += DifferentiableModel.Parameter("gateLogGamma", gateLogGamma)
        }
        return params
    }

    override fun loss(manager: NDManager): NDArray {
        val eps = 1e-7f
        val logit = x.matMul(w).add(b)                    // [n]
        var p = sigmoid(logit)
        if (useGate) {
            // 乘性软门控：g_i = σ(γ_g·(τ_g − r_i))，γ_g = exp(logGamma) 保正。p_i ← σ(logit_i)·g_i。
            val gamma = gateLogGamma.exp()
            val gate = sigmoid(gateTau.sub(r).mul(gamma))
            p = p.mul(gate)
        }
        p = p.clip(eps, 1f - eps)
        val oneMinusP = manager.create(1.0f).sub(p)
        val oneMinusY = manager.create(1.0f).sub(y)
        val dataLoss = when (lossKind) {
            LossKind.FOCAL -> {
                // FocalBCE（最大似然 → 优化全局排序≈AUC）。
                val posTerm = y.mul(oneMinusP.pow(gammaF)).mul(p.log()).mul(alphaF)
                val negTerm = oneMinusY.mul(p.pow(gammaF)).mul(oneMinusP.log()).mul(1f - alphaF)
                posTerm.add(negTerm).mean().neg()
            }
            LossKind.SOFT_FBETA -> {
                // 可微 soft-Fβ（直接优化 P/R 工作点，β<1 偏精度冲 P80）。软混淆矩阵用概率代替硬计数：
                //   TP=Σ y·p, FP=Σ (1−y)·p, FN=Σ y·(1−p)
                //   Fβ = (1+β²)·TP / [(1+β²)·TP + β²·FN + FP]，最大化 Fβ ⟺ 最小化 −Fβ。
                // 与似然不同：似然在每个样本上独立用力，Fβ 用力在「整体 P/R 平衡」——把梯度从中分区挪向高精度区。
                val b2 = (fBeta * fBeta).toFloat()
                val tp = y.mul(p).sum()
                val fp = oneMinusY.mul(p).sum()
                val fn = y.mul(oneMinusP).sum()
                val num = tp.mul(1f + b2)
                val den = tp.mul(1f + b2).add(fn.mul(b2)).add(fp).add(eps)
                num.div(den).neg()
            }
        }
        val l2Term = w.square().sum().mul(l2F)             // L2 正则：压制无用因子，使「吃全部因子」不过拟合
        val lossArr = dataLoss.add(l2Term)
        val lossVal = lossArr.toFloatArray()[0].toDouble()
        if (lossVal < bestLoss && lossVal.isFinite()) {
            bestLoss = lossVal
            val wf = w.toFloatArray()
            bestWeights = DoubleArray(k) { wf[it].toDouble() }
            bestBias = b.toFloatArray()[0].toDouble()
            if (useGate) {
                bestGateTau = gateTau.toFloatArray()[0].toDouble()
                bestGateGamma = kotlin.math.exp(gateLogGamma.toFloatArray()[0].toDouble())
            }
        }
        return lossArr
    }

    /** 用学到的权重对任意样本打分（sigmoid logit × 软门控）。 */
    fun scoreOf(s: PivotReversalFeatures.Sample): Double {
        val v = featureVector(s, featureKeys)
        var logit = bestBias
        for (j in featureKeys.indices) logit += bestWeights[j] * v[j]
        val base = 1.0 / (1.0 + kotlin.math.exp(-logit))
        if (!useGate) return base
        val gate = 1.0 / (1.0 + kotlin.math.exp(-bestGateGamma * (bestGateTau - s.trendScore)))
        return base * gate
    }

    private fun sigmoid(a: NDArray): NDArray = a.neg().exp().add(1.0).pow(-1.0)

    companion object {
        /** 从 Sample 提取特征向量（与 featureKeys 对齐）。核心 z 字段 + extra 因子层（已 rolling-z）。 */
        fun featureVector(s: PivotReversalFeatures.Sample, keys: List<String>): DoubleArray =
            DoubleArray(keys.size) { j ->
                when (val key = keys[j]) {
                    "trendScore" -> s.trendScore
                    "zNegRIntra" -> s.zNegRIntra
                    "zPcloseGap" -> s.zPcloseGap
                    "zSUpper" -> s.zSUpper
                    "zDoc" -> s.zDoc
                    "deltaIntra" -> s.deltaIntra
                    else -> s.extra[key] ?: 0.0
                }
            }

        /** 全特征键：核心 6 个 + 某样本的全部 extra 因子键（让 DJL 吃下全部因子，L2 压制无用项）。 */
        fun allFeatureKeys(sample: PivotReversalFeatures.Sample): List<String> =
            listOf("trendScore", "zNegRIntra", "zPcloseGap", "zSUpper", "zDoc", "deltaIntra") +
                sample.extra.keys.toList()
    }
}

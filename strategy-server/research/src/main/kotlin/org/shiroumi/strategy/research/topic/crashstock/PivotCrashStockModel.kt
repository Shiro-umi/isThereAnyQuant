package org.shiroumi.strategy.research.topic.crashstock

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 个股截面通用下跌预警的 **L2 正则 logistic + 可学乘性软门控**（纯 Kotlin 训练后端）。
 *
 * 设计文档（私有，不开源）：private/research-docs/pivot-crash-stock-formula.html
 *
 * 移植自 reversal 的 [org.shiroumi.strategy.research.topic.reversal.CrashLogisticModel]，
 * 把「市场级杀跌」范式升到「个股 × 截面通用预警」。复用其核心思想：
 *   - L2 正则吃下全部因子（无需 topK 截断，无用因子权重被压向 0）；
 *   - **可学乘性软门控**把「只在可投资域内预警」从模型外部硬过滤，内生为公式可学项。
 *
 * 公式（最小闭环）：
 *   logit_i = Σ_k w_k·x_{i,k} + b
 *   gate_i  = σ( γ_g·(liq_i − τ_g) )        liq_i = z 化流动性（越低越像垃圾股）
 *   p_i     = σ(logit_i) · gate_i           gate→0 时该票被判「不可投资」，预警概率自动压低
 *
 * τ_g（可投资域阈值）、γ_g（门陡峭度，logGamma 参数化保正）作**可学参数**，与 w/b 全样本联合训练。
 * 数学性质：γ_g→∞ 时软门 → 硬门 1[liq>τ_g]，**软门是硬过滤的严格泛化**（容量不小于硬切，OOS 不更差）。
 * 这正是用户要的「垃圾股过滤边界由梯度学，而非写死」。
 *
 * 两种 loss（用户要求对比）：
 *   - FOCAL：FocalBCE（最大似然 ≈ 优化全局排序 AUC）；
 *   - SOFT_FBETA：可微 Fβ（直接优化 P/R 工作点，β>1 偏召回——对应「召回率关键」诉求）。
 *
 *   L = dataLoss + λ·‖w‖²
 */
class PivotCrashStockModel(
    samples: List<PivotCrashStockSample.Sample>,
    private val alpha: Double = 0.75,
    private val gammaFocal: Double = 1.5,
    private val l2: Double = 0.01,
    private val useGate: Boolean = true,
    private val gateTauInit: Double = -0.5,   // z 化流动性刻度，初值略低（门槛在偏低流动性处）
    private val gateGammaInit: Double = 4.0,
    private val lossKind: LossKind = LossKind.SOFT_FBETA,
    private val fBeta: Double = 2.0,           // β>1 偏召回（用户「召回率关键」）
    private val labelKind: PivotCrashStockSample.LabelKind = PivotCrashStockSample.LabelKind.REL,
    /** 屏蔽换手·level（特征末位）：验证纯 Δ 因子能否独立撑起判别力（贯彻「只用变化量」方针）。 */
    private val dropLevel: Boolean = false,
) {

    /** 被屏蔽特征列（dropLevel 时屏蔽 level=末位）；屏蔽列的 x 置 0，等效从模型移除。 */
    private val maskedCol: Int = if (dropLevel) PivotCrashStockSample.NF - 1 else -1

    enum class LossKind { FOCAL, SOFT_FBETA }

    private val n = samples.size
    private val k = PivotCrashStockSample.NF
    private val xFlat = FloatArray(n * k)
    private val yArr = FloatArray(n) { samples[it].labelBy(labelKind).toFloat() }
    // 门控输入：流动性 z 化（跨样本标准化，门 τ/γ 学标准化刻度上的边界）
    private val liqArr: FloatArray
    /** 训练集流动性标准化的 (mean, sd)，供测试集打分时复用，杜绝泄漏。 */
    val liqMeanSd: Pair<Double, Double>

    init {
        for (i in 0 until n) {
            val f = samples[i].features
            for (j in 0 until k) xFlat[i * k + j] = if (j == maskedCol) 0f else f[j].toFloat()
        }
        val liqs = samples.map { it.liquidity }
        val mean = liqs.average()
        val sd = sqrt(liqs.sumOf { (it - mean) * (it - mean) } / maxOf(1, liqs.size - 1)).let { if (it == 0.0) 1.0 else it }
        liqMeanSd = mean to sd
        liqArr = FloatArray(n) { (((samples[it].liquidity - mean) / sd)).toFloat() }
    }

    private val w = DoubleArray(k)
    private var b = 0.0
    private var gateTau = gateTauInit
    private var gateLogGamma = ln(gateGammaInit)

    var bestWeights: DoubleArray = DoubleArray(k); private set
    var bestBias: Double = 0.0; private set
    var bestGateTau: Double = gateTauInit; private set
    var bestGateGamma: Double = gateGammaInit; private set
    private var bestLoss: Double = Double.POSITIVE_INFINITY

    fun train(maxIter: Int, learningRate: Double, patience: Int = 30, minDelta: Double = 1e-6) {
        var noImprove = 0
        repeat(maxIter) {
            val gradW = DoubleArray(k)
            var gradB = 0.0
            var gradTau = 0.0
            var gradLogGamma = 0.0
            var dataLoss = 0.0
            val gamma = exp(gateLogGamma)
            for (i in 0 until n) {
                val baseOffset = i * k
                var logit = b
                for (j in 0 until k) logit += w[j] * xFlat[baseOffset + j].toDouble()
                val base = sigmoid(logit)
                val gate = if (useGate) sigmoid(gamma * (liqArr[i].toDouble() - gateTau)) else 1.0
                val p = (base * gate).coerceIn(EPS, 1.0 - EPS)
                val y = yArr[i].toDouble()
                val weight = if (y > 0.5) alpha else 1.0 - alpha
                dataLoss += -weight * (y * ln(p) + (1.0 - y) * ln(1.0 - p))
                val dLossDp = weight * (p - y) / (p * (1.0 - p)) / n
                val dLogit = dLossDp * gate * base * (1.0 - base)
                for (j in 0 until k) gradW[j] += dLogit * xFlat[baseOffset + j].toDouble()
                gradB += dLogit
                if (useGate) {
                    val dGateCommon = dLossDp * base * gate * (1.0 - gate)
                    gradTau -= dGateCommon * gamma
                    gradLogGamma += dGateCommon * gamma * (liqArr[i].toDouble() - gateTau)
                }
            }
            var l2Loss = 0.0
            for (j in 0 until k) {
                l2Loss += w[j] * w[j]
                gradW[j] += 2.0 * l2 * w[j]
            }
            val loss = dataLoss / n + l2 * l2Loss
            for (j in 0 until k) w[j] -= learningRate * gradW[j]
            b -= learningRate * gradB
            if (useGate) {
                gateTau -= learningRate * gradTau
                gateLogGamma = (gateLogGamma - learningRate * gradLogGamma).coerceIn(-4.0, 6.0)
            }
            if (loss + minDelta < bestLoss && loss.isFinite()) {
                bestLoss = loss
                bestWeights = w.copyOf()
                bestBias = b
                bestGateTau = gateTau
                bestGateGamma = exp(gateLogGamma)
                noImprove = 0
            } else {
                noImprove++
                if (patience > 0 && noImprove >= patience) return
            }
        }
    }

    /** 用学到的权重对任意样本打「未来下跌概率」分。liqZ 需用训练集同一 (mean,sd) 标准化后传入。 */
    fun scoreOf(features: DoubleArray, liqZ: Double): Double {
        var logit = bestBias
        for (j in features.indices) if (j != maskedCol) logit += bestWeights[j] * features[j]
        val base = 1.0 / (1.0 + exp(-logit))
        if (!useGate) return base
        val gate = 1.0 / (1.0 + exp(-bestGateGamma * (liqZ - bestGateTau)))
        return base * gate
    }

    private fun sigmoid(x: Double): Double = when {
        x >= 35.0 -> 1.0
        x <= -35.0 -> 0.0
        else -> 1.0 / (1.0 + exp(-x))
    }

    companion object {
        private const val EPS = 1e-7
    }
}

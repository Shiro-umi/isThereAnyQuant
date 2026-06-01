package org.shiroumi.strategy.research.topic.reversal

import kotlin.math.exp
import kotlin.math.pow

/**
 * 次日反转概率融合公式（§3.6）的纯 Kotlin 打分器。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §3.6 crown 公式：
 *
 *   raw_t = G_{t-1} · ( M_{t-1} + μ·V_{t-1} + λ·Ã_{t-1} )
 *   P^rev_t = ECDF_train( raw_t )
 *
 * 其中（全部取自 t−1 及更早，由 [PivotReversalFeatures.Sample] 一次性对齐，零前视）：
 * - M_{t-1} = w1·z(−R_intra) + w2·z(0.5−P_close) + w3·z(S_upper) + w4·z(D_oc)   —— 前日价形态
 * - V_{t-1} = θ1·z(VP1) + θ2·z(−VP2c) + θ3·z(VP2v) + θ4·z(−VPβ)               —— t−1 量能领先（主预测项）
 * - G^V_{t-1} = 1 + κ·σ(ρ·(z(\dot v) − \dot v*))，Ã = G^V·max(0,−Δ)^γ          —— 量门控 × 价裂缝
 * - G_{t-1} = σ(β·(P^up − 0.5))                                                 —— 前序亢奋背离基准
 *
 * 概率连接 ECDF 用**训练集 raw 分布**拟合（[fitEcdf]），对任意样本的 raw 给出经验分位 ∈[0,1]，
 * 与趋势研究同一套概率语义。打分器无状态、参数全外置，供黑盒 / 可微 tuner 反复调用。
 */
class PivotReversalScorer(private val p: Params) {

    /**
     * 融合公式全部连续参数（§3.6）。默认值是「未调优」的合理先验，真正的最优值由 tuner 在历史样本上学习。
     * - 价侧：w1..w4（形态权重）、k（恶化窗，整数，在 Features 段固定）、gamma（恶化幂放大）、beta（背离锐度）、lambda（恶化项总权重）
     * - 量价：theta1..theta4（量能领先权重）、mu（量能领先总权重）、kappa/rho/vStar（量门控）
     */
    data class Params(
        val w1: Double = 1.0, val w2: Double = 1.0, val w3: Double = 1.0, val w4: Double = 1.0,
        val gamma: Double = 1.0,
        val beta: Double = 2.0,
        val lambda: Double = 1.0,
        val theta1: Double = 1.0, val theta2: Double = 1.0, val theta3: Double = 1.0, val theta4: Double = 1.0,
        val mu: Double = 1.0,
        val kappa: Double = 1.0, val rho: Double = 1.0, val vStar: Double = 0.0,
        /** trendScore 直接线性项权重（实测强反向 IC=−0.303，期望学成负；不再只藏在乘性 G 里）。 */
        val wTrend: Double = 0.0,
        /**
         * 情绪因子层 top-K 的可学线性权重（因子 key → 权重，符号自由）。
         * 体检 B 证明这是突破纯 11 因子 AUC 0.64 天花板的金矿（B7/B5/B4/D1/C3… IC 显著）。
         */
        val sentimentWeights: Map<String, Double> = emptyMap(),
        /** 纯价基线开关：true 时 μ=κ=0、情绪项=0，退化为 §3.5 raw^(price)，用于 §3.6 消融对比。 */
        val priceOnly: Boolean = false,
    )

    /** 前日价形态分 M_{t-1}。 */
    fun priceShape(s: PivotReversalFeatures.Sample): Double =
        p.w1 * s.zNegRIntra + p.w2 * s.zPcloseGap + p.w3 * s.zSUpper + p.w4 * s.zDoc

    /** t−1 量能领先分 V_{t-1}（主预测项）。 */
    fun volumeLead(s: PivotReversalFeatures.Sample): Double =
        p.theta1 * s.zVp1 + p.theta2 * s.zNegVp2c + p.theta3 * s.zVp2v + p.theta4 * s.zNegVpBeta

    /** 量门控后的价裂缝 Ã_{t-1} = G^V · max(0,−Δ)^γ。priceOnly 时门控退化为 1。 */
    fun gatedDeterioration(s: PivotReversalFeatures.Sample): Double {
        val crack = maxOf(0.0, -s.deltaIntra).pow(p.gamma)
        if (crack == 0.0) return 0.0
        val gate = if (p.priceOnly) 1.0 else 1.0 + p.kappa * sigmoid(p.rho * (s.zVdot - p.vStar))
        return gate * crack
    }

    /** 前序亢奋背离基准 G_{t-1} = σ(β·(P^up − 0.5)) ∈ (0,1)。 */
    fun divergenceBasis(s: PivotReversalFeatures.Sample): Double =
        sigmoid(p.beta * (s.trendScore - 0.5))

    /** 情绪因子层线性分 Σ φ_k·z(sent_k)（金矿项；priceOnly 时为 0）。 */
    fun sentimentScore(s: PivotReversalFeatures.Sample): Double {
        if (p.priceOnly || p.sentimentWeights.isEmpty()) return 0.0
        var sum = 0.0
        for ((k, w) in p.sentimentWeights) sum += w * (s.extra[k] ?: 0.0)
        return sum
    }

    /**
     * 单样本 raw 分（未经 ECDF）。
     *
     * raw = G·(M + μV + λÃ)  +  w_trend·trendScore  +  Σ φ_k·z(sent_k)
     *
     * 乘性项保留文档「前序亢奋 × 当前状态分」语义；trendScore 与情绪因子额外以**带符号线性项**进入，
     * 让 tuner 表达乘性 G 表达不了的强反向/线性叠加信号（实测金矿）。priceOnly 时只剩纯价乘性项。
     */
    fun raw(s: PivotReversalFeatures.Sample): Double {
        val m = priceShape(s)
        val vTerm = if (p.priceOnly) 0.0 else p.mu * volumeLead(s)
        val aTerm = p.lambda * gatedDeterioration(s)
        val multiplicative = divergenceBasis(s) * (m + vTerm + aTerm)
        if (p.priceOnly) return multiplicative
        return multiplicative + p.wTrend * s.trendScore + sentimentScore(s)
    }

    /** 批量 raw 分。 */
    fun rawAll(samples: List<PivotReversalFeatures.Sample>): DoubleArray =
        DoubleArray(samples.size) { raw(samples[it]) }

    companion object {
        private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        /**
         * 用训练集 raw 分布拟合 ECDF（升序排好的训练 raw），返回一个把任意 raw 映射到经验分位 ∈[0,1] 的函数。
         * 严格只用训练集分布（§3.6/§7：ECDF_train），对 val/test 样本是「样本外」连接，杜绝用测试分布泄漏。
         */
        fun fitEcdf(trainRaw: DoubleArray): (Double) -> Double {
            val sorted = trainRaw.filter { it.isFinite() }.sorted()
            val m = sorted.size
            if (m == 0) return { 0.5 }
            return { x ->
                // 经验分位 = #(train ≤ x) / m，二分定位
                var lo = 0; var hi = m
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (sorted[mid] <= x) lo = mid + 1 else hi = mid
                }
                lo.toDouble() / m
            }
        }
    }
}

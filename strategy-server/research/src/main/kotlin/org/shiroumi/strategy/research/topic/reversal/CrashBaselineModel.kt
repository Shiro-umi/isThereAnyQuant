package org.shiroumi.strategy.research.topic.reversal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp

/**
 * 极端行情（大阴线杀跌）预测 **baseline 模型** —— 固化的可上线预测器。
 *
 * 这是 [CrashDifferentiableModel] 在全样本训练后导出的「权重 + 阈值 + 因子契约」快照，
 * 与训练框架解耦：实盘侧只需 [fromJson] 这份 JSON、按 [featureKeys] 顺序备好特征向量，调 [predict] 即得
 * 杀跌概率 P̂∈[0,1]，再用 [thresholds] 里选定的工作点阈值判预警。不依赖 DJL、不触账户层。
 *
 * 打分逻辑（与训练侧 [CrashDifferentiableModel.scoreOf] 严格一致，baseline 不启用软门控）：
 *   logit = bias + Σ_j weights[j]·features[j]
 *   P̂    = σ(logit) = 1/(1+e^{-logit})
 *
 * 标签口径：市场级市值加权日内 (收−开)/昨收 ≤ −crashThreshold（默认 1%）= 大阴线日。
 * 特征全部取 t−1 及更早（严格因果），预测 t 日是否大阴线。
 */
@Serializable
data class CrashBaselineModel(
    /** 模型版本/训练区间等元信息，便于实盘追溯。 */
    val meta: Meta,
    /** 因子键顺序（实盘必须按此顺序构造特征向量）。 */
    val featureKeys: List<String>,
    /** 与 featureKeys 同序的权重。 */
    val weights: List<Double>,
    /** 偏置。 */
    val bias: Double,
    /** 选定工作点阈值（precision 门 / recall 门），实盘按业务场景选用。 */
    val thresholds: List<Threshold>,
) {
    @Serializable
    data class Meta(
        val name: String,           // 如 "crash_baseline_osc_hurst"
        val trainStart: String,
        val trainEnd: String,
        val sampleCount: Int,
        val positiveCount: Int,
        val featureCount: Int,
        val l2: Double,
        val wfAuc: Double,          // walk-forward OOS AUC（泛化力参考）
        val crashThreshold: Double, // 大阴线判定阈值（日内跌幅）
        val note: String = "",
    )

    /** 一个工作点：name=语义（如 recall90/precision70），tau=判预警阈值，附 OOS 实测 P/R。 */
    @Serializable
    data class Threshold(
        val name: String,
        val tau: Double,
        val precision: Double,
        val recall: Double,
    )

    /** 对一条按 [featureKeys] 顺序排列的特征向量打分，返回大阴线概率 P̂∈[0,1]。 */
    fun predict(features: DoubleArray): Double {
        require(features.size == weights.size) { "特征维度 ${features.size} != 权重维度 ${weights.size}" }
        var logit = bias
        for (j in weights.indices) logit += weights[j] * features[j]
        return 1.0 / (1.0 + exp(-logit))
    }

    /** 用某工作点阈值判定是否发出大阴线预警。 */
    fun alert(features: DoubleArray, thresholdName: String): Boolean {
        val t = thresholds.firstOrNull { it.name == thresholdName }
            ?: error("未知工作点 '$thresholdName'，可选：${thresholds.joinToString { it.name }}")
        return predict(features) >= t.tau
    }

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        fun fromJson(text: String): CrashBaselineModel = json.decodeFromString(text)
    }
}

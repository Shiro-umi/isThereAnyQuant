package org.shiroumi.strategy.research.tuner.sentiment

/**
 * 次日情绪加强概率公式的结构性参数（v2 · 9 参数，D 树已合并至 A 树）。
 *
 * 初值 = 恒等映射：全部 θ 取 identity 时，公式输出与纯数据自洽形态完全一致。
 */
data class ThetaConfig(
    /** 对熵生成 α 的偏移 · ∈ (-1.0, 1.0) · v2 放宽 */
    val thetaAlpha: Double = 0.0,
    /** 玻尔兹曼熵权 softmax 温度 · ∈ (0.05, 10.0) · v2 放宽 */
    val thetaLambda: Double = 1.0,
    /** chain 链路耦合强度增益 · ∈ (0.1, 3.0) */
    val thetaXi: Double = 1.0,
    /** 三棵树各自的 F1b↔F2a 信噪比偏置 · ∈ (-0.3, 0.3) */
    val thetaGammaA: Double = 0.0,
    val thetaGammaB: Double = 0.0,
    val thetaGammaC: Double = 0.0,
    /** 温度衰减幂乘子 · ∈ (0.5, 2.5) */
    val thetaBeta: Double = 1.0,
    /** 三棵方向树在 E_sys 中的 softmax logit · ∈ (-3.0, 3.0) */
    val thetaWA: Double = 0.0,
    val thetaWB: Double = 0.0,
    val thetaWC: Double = 0.0,
    /** 半衰期对数偏移 · ∈ (-6.0, 6.0) · v3 再放宽 */
    val thetaTau: Double = 0.0,
) {
    companion object {
        val IDENTITY = ThetaConfig()

        fun fromParams(params: Map<String, String>): ThetaConfig = ThetaConfig(
            thetaAlpha = params["thetaAlpha"]?.toDoubleOrNull() ?: 0.0,
            thetaLambda = params["thetaLambda"]?.toDoubleOrNull() ?: 1.0,
            thetaXi = params["thetaXi"]?.toDoubleOrNull() ?: 1.0,
            thetaGammaA = params["thetaGammaA"]?.toDoubleOrNull() ?: 0.0,
            thetaGammaB = params["thetaGammaB"]?.toDoubleOrNull() ?: 0.0,
            thetaGammaC = params["thetaGammaC"]?.toDoubleOrNull() ?: 0.0,
            thetaBeta = params["thetaBeta"]?.toDoubleOrNull() ?: 1.0,
            thetaWA = params["thetaWA"]?.toDoubleOrNull() ?: 0.0,
            thetaWB = params["thetaWB"]?.toDoubleOrNull() ?: 0.0,
            thetaWC = params["thetaWC"]?.toDoubleOrNull() ?: 0.0,
            thetaTau = params["thetaTau"]?.toDoubleOrNull() ?: 0.0,
        )

        /** v2 · 9 参数搜索空间（D 树已合并至 A 树，θα/θλ/θτ 放宽） */
        val SEARCH_BOUNDS = listOf(
            "thetaAlpha" to (-1.0 to 1.0),
            "thetaLambda" to (0.05 to 10.0),
            "thetaXi" to (0.1 to 3.0),
            "thetaGammaA" to (-0.3 to 0.3),
            "thetaGammaB" to (-0.3 to 0.3),
            "thetaGammaC" to (-0.3 to 0.3),
            "thetaBeta" to (0.5 to 2.5),
            "thetaWA" to (-3.0 to 3.0),
            "thetaWB" to (-3.0 to 3.0),
            "thetaWC" to (-3.0 to 3.0),
            "thetaTau" to (-6.0 to 6.0),
        )
    }
}

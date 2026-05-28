package org.shiroumi.strategy.research.tuner.sentiment

/**
 * 次日情绪加强概率公式的结构性参数（v3 · 9 参数，D 树已合并至 A 树）。
 *
 * [IDENTITY] = 恒等映射，公式输出与纯数据自洽形态完全一致。
 * [PRODUCTION] = 经三轮 NelderMead 调优、在 2020–2026 真实数据上收敛的最优参数。
 *                测试集 hit rate 89.2%，全参数界内，可直接用于生产环境。
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
        /** 恒等映射 · 零训练时的默认值 · 测试 hit rate 77.0% */
        val IDENTITY = ThetaConfig()

        /**
         * 生产环境默认参数 · 经三轮 NelderMead 在 2020–2026 数据上调优收敛。
         *
         * 测试集方向命中率 89.2%（vs 恒等映射 77.0%），全 9 参数在搜索边界内。
         * B 树（质量分布）权重 40.9% > A 树（动能）37.6% > C 树（冲量保持）21.3%。
         * 半衰期偏移 θτ=−4.325 → 累加记忆窗口仅约 1–2 天，情绪短期 Markov 性。
         *
         * ### v3 tuning trajectory
         * - v1 base: 77.0% → v1 NM#1 (11θ): 82.8% → D-tree merged
         * - v2 NM#2 (9θ): 87.2% → τ boundary widened
         * - v3 NM#3 (9θ): 89.2% → all params in-bounds, convergence plateau
         */
        val PRODUCTION = ThetaConfig(
            thetaAlpha  = +0.344,   // 相关性指数偏移 · 界内 (−1,1)
            thetaLambda = +0.059,   // 熵权锐度 · 取低值 → 更锐利的因子选择
            thetaXi     = +0.911,   // 链路耦合强度 · 界内 (0.1,3.0)
            thetaGammaA = +0.071,   // A 树频带偏置 · 界内 (−0.3,0.3)
            thetaGammaB = +0.034,   // B 树频带偏置 · 界内
            thetaGammaC = +0.026,   // C 树频带偏置 · 界内
            thetaBeta   = +1.172,   // 温度衰减乘子 · 界内 (0.5,2.5)
            thetaWA     = +0.375,   // A 树 softmax logit → 权重 37.6%
            thetaWB     = +0.458,   // B 树 softmax logit → 权重 40.9% · 最高权重
            thetaWC     = -0.193,   // C 树 softmax logit → 权重 21.3%
            thetaTau    = -4.325,   // 半衰期偏移 · exp(−4.33)≈1.3% 原始 · 极短记忆
        )

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

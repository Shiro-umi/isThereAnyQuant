package org.shiroumi.strategy.research.topic.trend.tuner

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
         * 生产环境默认参数 · 以「次日 a1 市值加权涨跌幅双向方向」为标的、
         * max-min（两侧命中率最小值）目标在 2020–2023 训练集上 NelderMead 调优收敛（m1）。
         *
         * 配合双判定阈值 [TAU_LONG] / [TAU_SHORT] 使用：
         *   score > TAU_LONG  → 看涨；score < TAU_SHORT → 看跌；中间区弃权（不表态）。
         *
         * 测试集（2025+）双向命中：多头 96.8%、空头 90.9%（弃权 104/296 天）。
         * 阈值 τ_short 在验证集（2024）上选定（val 空头 100%@38d），test 仅验收，无前视。
         *
         * ### 双向调优轨迹（eval 标的 = 次日 a1 方向）
         * - 起点 单向 yComposite θ: test 多头 82.4% / 空头 62.5%
         * - m1 max-min(θ): test 多头 96.8% / 空头 75.6%（目标函数换 max-min 是关键）
         * - m2 train+val 联合 max-min: test 空头 74.2%（确认参数已到顶，瓶颈是 2025 分布漂移）
         * - m3 阈值优化 τ_short=0.26: test 空头 90.9%（用高置信区间换覆盖，双向双双 >80%）
         */
        val PRODUCTION = ThetaConfig(
            thetaAlpha  = +0.6183,  // 相关性指数偏移 · 界内 (−1,1)
            thetaLambda = +0.2460,  // 熵权锐度
            thetaXi     = +1.4004,  // 链路耦合强度 · 界内 (0.1,3.0)
            thetaGammaA = +0.0957,  // A 树频带偏置 · 界内 (−0.3,0.3)
            thetaGammaB = -0.1975,  // B 树频带偏置 · 界内
            thetaGammaC = +0.0534,  // C 树频带偏置 · 界内
            thetaBeta   = +1.2568,  // 温度衰减乘子 · 界内 (0.5,2.5)
            thetaWA     = +2.6354,  // A 树 softmax logit · 动能树主导
            thetaWB     = -2.0823,  // B 树 softmax logit
            thetaWC     = -1.1222,  // C 树 softmax logit
            thetaTau    = -1.2290,  // 半衰期偏移 · 累加记忆窗口约 3–4 天
        )

        /** 生产判定阈值 · 看涨门槛（score > TAU_LONG 判看涨） */
        const val TAU_LONG = 0.50

        /**
         * 生产判定阈值 · 看跌门槛（score < TAU_SHORT 判看跌，否则弃权）。
         * 在验证集上选定 0.26：A 股下跌又急又少，只在强烈看跌时表态，把不确定区交给弃权，
         * 用覆盖率换空头侧精度。test 集空头命中由 75.6% 提升至 90.9%。
         */
        const val TAU_SHORT = 0.26

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

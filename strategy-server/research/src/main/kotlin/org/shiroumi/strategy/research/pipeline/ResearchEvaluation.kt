package org.shiroumi.strategy.research.pipeline

/**
 * 「研究内容」插槽之二 —— 对研究迭代结果的后处理 / 评估（子模块 ②）。
 *
 * 吃 [ResearchStudy]（①）产出的**未裁判原始度量** [RawMetric]，按当前研究 topic 的评估口径
 * 裁出最终判定 [Verdict]（如：是否合格、分档、可落盘的结论卡片）。
 *
 * 与 [ResearchStudy] 的分工：
 * - ①Study：诚实算度量，**不裁判**。产物是中立、可复用的原始度量。
 * - ②Evaluation：**裁判器**。吃①的度量，对照阈值（如执行手册 §12.4 的 12 项硬条件）出 qualified。
 *   评估口径随 topic 不同（情绪因子有一套，换研究换一套），**但评估结论本身不作为可复用产物对外暴露**
 *   —— 它只是为①服务的裁判，可复用的是①的原始度量。
 *
 * `qualified` 的归属在这里，不在 Study：把"研究"与"裁判"分开，避免研究循环为了过 Metric 自我放水。
 *
 * 泛型形状统一、类型各研究自定：
 * - 情绪因子研究：`ResearchEvaluation<List<ResonanceMetric>, List<ResonanceCard>>`
 *
 * @param RawMetric ①Study 产出的未裁判度量类型
 * @param Verdict   裁判后的最终产物类型（交给 Output 段落盘）
 */
interface ResearchEvaluation<RawMetric, Verdict> : ResearchStage<RawMetric, Verdict> {

    /** 评估口径的可读标识，用于追溯（如 "sentiment-factor-12gate"）。 */
    val name: String

    /**
     * 评估①的原始度量并裁出最终判定。只读 [input] 与 [ctx] 的阈值参数，
     * 不得回头改研究逻辑、不得放宽硬条件骗 Metric。
     */
    override fun run(ctx: ResearchContext, input: RawMetric): Verdict
}

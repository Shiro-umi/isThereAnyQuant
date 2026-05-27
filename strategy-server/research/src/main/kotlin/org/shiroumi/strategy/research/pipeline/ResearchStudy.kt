package org.shiroumi.strategy.research.pipeline

/**
 * 「研究内容」插槽之一 —— 研究迭代代码本身（子模块 ①）。
 *
 * 这是整条管线里**交给 autoresearch 迭代**的核心部分。autoresearch 在这里实现具体研究逻辑
 * （例如：因子计算、Y 标签、状态划分、STFT 分频、相干性与领先关系、permutation 显著性、
 * 样本外验证），而不触碰上下游的工程基建（Source / 信号能力层 / 落盘）。
 *
 * **职责边界（关键）**：Study 只**诚实算出原始度量**（mean_coherence / oos_ic / lead_days …），
 * **不自我裁判**。「研究结果好不好、是否合格」由独立的 [ResearchEvaluation]（子模块 ②）判定。
 * 这样 Study 的产物 [O] 是**中立、未裁判的原始度量**，可被下游直接复用，不被某套评估口径绑架。
 *
 * 它是一个语义特化的 [ResearchStage]：
 * - 输入 [I]：上游 Input 段交付的、已对齐清洗的研究输入（各研究自定 dataclass）。
 * - 输出 [O]：**未裁判的原始度量**产物（各研究自定 dataclass），交给 [ResearchEvaluation] 评估。
 *
 * 泛型形状统一、具体类型各研究自定：
 * - 情绪因子研究：`ResearchStudy<FactorPanel, List<ResonanceMetric>>`
 * - 其它研究可定义自己的 In/Out，但都遵守同一接口形状。
 *
 * @param I 研究输入类型（Input 段产出）
 * @param O 未裁判的原始度量类型（交给 [ResearchEvaluation]）
 */
interface ResearchStudy<I, O> : ResearchStage<I, O> {

    /** 研究内容的可读标识，用于产物追溯与日志（如 "sentiment-factor-resonance"）。 */
    val name: String

    /**
     * 执行研究内容，产出**未裁判的原始度量**。实现方只依赖 [ctx] 的参数/区间/种子与上游 [input]，
     * 必要的中间态落 [ctx] 工作区文件。不在此判定合格与否——交给 [ResearchEvaluation]。
     */
    override fun run(ctx: ResearchContext, input: I): O
}

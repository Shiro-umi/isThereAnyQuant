package org.shiroumi.strategy.research.pipeline

/**
 * 「研究内容」插槽 —— 七段管线中 Study 那一段的抽象。
 *
 * 这是整条管线里**唯一交给 autoresearch 迭代**的部分。autoresearch 在这里实现具体研究逻辑
 * （例如：因子计算、Y 标签、状态划分、STFT 分频、相干性与领先关系、permutation 显著性、
 * 样本外验证、出共振卡片），而不触碰上下游的工程基建（Source / 信号能力层 / 落盘 / 比对）。
 *
 * 它是一个语义特化的 [ResearchStage]：
 * - 输入 [I]：上游 Input 段交付的、已对齐清洗的研究输入（事实数据 + 研究参数解释后的视图）。
 * - 输出 [O]：研究产物（如共振卡片集合），交给下游 Output 段落盘。
 *
 * 之所以独立成接口而非直接用 [ResearchStage]，是为了在类型与文档上**钉死职责边界**：
 * 看到 `ResearchStudy` 就知道这是 autoresearch 的领地，其余 Stage 是基建不可被研究循环改动。
 *
 * @param I 研究输入类型（Input 段产出）
 * @param O 研究产物类型（交给 Output 段）
 */
interface ResearchStudy<I, O> : ResearchStage<I, O> {

    /** 研究内容的可读标识，用于产物追溯与日志（如 "sentiment-factor-resonance"）。 */
    val name: String

    /**
     * 执行研究内容。实现方只依赖 [ctx] 的参数/区间/种子与上游 [input]，
     * 产物通过返回值交给下游，必要的中间态落 [ctx] 工作区文件。
     */
    override fun run(ctx: ResearchContext, input: I): O
}

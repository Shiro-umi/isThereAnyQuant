package org.shiroumi.strategy.research.pipeline

/**
 * 研究管线中的一段【纯变换】：给定上下文与上游输入，产出下游输入。
 *
 * 七段管线的每一段都是一个 [ResearchStage]：
 *
 * ```
 * Source ──▶ Transform ──▶ Input ──▶ Study ──▶ Output ──▶ Compare ──▶ Conclusion
 *  事实K线     清洗/对齐    研究输入   研究内容    产物      比对/统计     结论/可视化
 * ```
 *
 * 设计约束：
 * - 一段只做一件事，输入输出类型显式声明，便于上下游通过【规范化 I/O】复用。
 * - 段内可以借 [ResearchContext.workspace] 落中间态文件，但不得写数据库表。
 * - 「研究内容」那一段由 [ResearchStudy] 承担，是 autoresearch 唯一负责迭代的插槽；
 *   其余各段是工程基建，形态稳定。
 *
 * @param I 上游输入类型
 * @param O 本段产出类型（即下游的输入）
 */
fun interface ResearchStage<I, O> {
    /**
     * 执行本段变换。
     *
     * @param ctx   贯穿全程的研究上下文（样本区间、参数、工作区、随机种子）
     * @param input 上游产出
     * @return 本段产出，作为下游输入
     */
    fun run(ctx: ResearchContext, input: I): O
}

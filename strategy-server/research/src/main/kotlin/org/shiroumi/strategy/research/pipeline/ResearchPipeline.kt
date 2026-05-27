package org.shiroumi.strategy.research.pipeline

/**
 * 通用研究管线：把七段 [ResearchStage] 串成一条类型安全的链路并顺序执行。
 *
 * ```
 * Source ──▶ Transform ──▶ Input ──▶ Study ──▶ Output ──▶ Compare ──▶ Conclusion
 * ```
 *
 * 语义锚点（七段职责）：
 * - **Source**：从 :database 读【事实】K 线 / 涨跌停 / 日历（不做任何加工）。
 * - **Transform**：清洗、对齐交易日、缺失处理，得到规整的时间序列。
 * - **Input**：按研究参数把规整数据组织成「研究输入」（截面/区间视图）。
 * - **Study**：[ResearchStudy] —— autoresearch 唯一迭代的研究内容。
 * - **Output**：把研究产物落盘成规范化文件（如共振卡片 JSON）。
 * - **Compare**：与基线/对照/历史轮次比对，产出度量。
 * - **Conclusion**：汇总结论与可视化所需结构。
 *
 * 设计要点：
 * - 链路通过 [andThen] 类型安全地拼接，前一段输出类型即后一段输入类型。
 * - 达成研究目标后，[ResearchStudy] 可凭规范化 I/O 被上下游复用（替换 Study 段即换研究主题）。
 * - 管线自身不写数据库表；中间态/产物一律经 [ResearchContext.workspace] 落文件。
 *
 * @param IN  整条管线的起始输入类型（通常是 Source 段的取数请求 / [Unit]）
 * @param OUT 整条管线的最终产出类型（通常是 Conclusion）
 */
class ResearchPipeline<IN, OUT> private constructor(
    private val head: ResearchStage<IN, OUT>,
    /** 链路各段的可读名称，按执行顺序排列，用于日志与产物追溯。 */
    val stageNames: List<String>,
) {
    /** 顺序执行整条管线。 */
    fun run(ctx: ResearchContext, input: IN): OUT = head.run(ctx, input)

    /** 在尾部追加一段，得到一条新的、更长的管线（类型在拼接处对齐）。 */
    fun <NEXT> andThen(name: String, next: ResearchStage<OUT, NEXT>): ResearchPipeline<IN, NEXT> =
        ResearchPipeline(
            head = ResearchStage { ctx, input -> next.run(ctx, head.run(ctx, input)) },
            stageNames = stageNames + name,
        )

    companion object {
        /** 以首段开启一条管线。 */
        fun <IN, OUT> from(name: String, stage: ResearchStage<IN, OUT>): ResearchPipeline<IN, OUT> =
            ResearchPipeline(stage, listOf(name))
    }
}

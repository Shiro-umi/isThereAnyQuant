package org.shiroumi.strategy.research.topic.factor.skeleton

import org.shiroumi.strategy.research.topic.factor.output.ResonanceCardWriter
import org.shiroumi.strategy.research.topic.factor.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import org.shiroumi.strategy.research.topic.factor.skeleton.FakeEvaluation
import org.shiroumi.strategy.research.topic.factor.skeleton.FakeStudy
import java.nio.file.Path

/**
 * 骨架自检管线：用 [FakeStudy] + [FakeEvaluation] 跑通管线最小闭环，验证基建链路类型对齐、产物能落盘。
 *
 * 这里把研究内容的两个子模块（①Study、②Evaluation）与 Output（落盘）串成一条 [ResearchPipeline]：
 *
 * ```
 * Study(FakeStudy) ──▶ Evaluation(FakeEvaluation) ──▶ Output(ResonanceCardWriter)
 *  ①出未裁判度量          ②裁判出 qualified              落盘卡片
 * ```
 *
 * Source/Transform/Input/Compare/Conclusion 在骨架阶段暂以直通占位（FakeStudy 不消费上游数据），
 * 真实研究接入时由 autoresearch 在①②两个插槽分别填充研究逻辑与评估口径，并按需接上 Source 段
 * （[org.shiroumi.strategy.research.topic.factor.source.ResearchKlineSource]）读事实 K 线。
 *
 * 关键不变量：FakeEvaluation 裁出 `qualified=false`，因此落盘后 `count-resonance-cards.sh` 仍输出 0。
 */
object SkeletonPipeline {

    /** 组装骨架管线（Study → Evaluation → Output），返回落盘后的卡片文件路径。 */
    fun build(): ResearchPipeline<Unit, List<Path>> {
        val study = FakeStudy()
        val evaluation = FakeEvaluation()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceMetric>>(study.name, study)
            .andThen(evaluation.name, evaluation)
            .andThen("output:resonance-card-writer", writer)
    }

    /** 执行骨架管线并返回落盘路径。 */
    fun run(ctx: ResearchContext): List<Path> = build().run(ctx, Unit)
}

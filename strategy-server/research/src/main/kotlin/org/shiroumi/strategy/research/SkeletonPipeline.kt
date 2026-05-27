package org.shiroumi.strategy.research

import org.shiroumi.strategy.research.output.ResonanceCard
import org.shiroumi.strategy.research.output.ResonanceCardWriter
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchPipeline
import org.shiroumi.strategy.research.pipeline.ResearchStage
import org.shiroumi.strategy.research.study.FakeStudy
import java.nio.file.Path

/**
 * 骨架自检管线：用 [FakeStudy] 跑通七段管线的最小闭环，验证基建链路类型对齐、产物能落盘。
 *
 * 这里把 Study（研究内容）与 Output（落盘）串成一条 [ResearchPipeline]：
 *
 * ```
 * Study(FakeStudy) ──▶ Output(ResonanceCardWriter)
 * ```
 *
 * Source/Transform/Input/Compare/Conclusion 在骨架阶段暂以直通占位（FakeStudy 不消费上游数据），
 * 真实研究接入时由 autoresearch 在 Study 插槽填充逻辑、并按需要接上 Source 段（[org.shiroumi
 * .strategy.research.source.ResearchKlineSource]）读事实 K 线。
 *
 * 关键不变量：FakeStudy 产出 `qualified=false`，因此落盘后 `count-resonance-cards.sh` 仍输出 0。
 */
object SkeletonPipeline {

    /** 组装骨架管线（Study → Output），返回落盘后的卡片文件路径。 */
    fun build(): ResearchPipeline<Unit, List<Path>> {
        val study = FakeStudy()
        val writer = ResonanceCardWriter()
        return ResearchPipeline
            .from<Unit, List<ResonanceCard>>(study.name, study)
            .andThen("output:resonance-card-writer", writer)
    }

    /** 执行骨架管线并返回落盘路径。 */
    fun run(ctx: ResearchContext): List<Path> = build().run(ctx, Unit)
}

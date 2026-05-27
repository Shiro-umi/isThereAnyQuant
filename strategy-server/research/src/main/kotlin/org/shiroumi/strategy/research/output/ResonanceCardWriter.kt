package org.shiroumi.strategy.research.output

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Output 段：把研究产出的共振卡片落盘成规范化 JSON 文件。
 *
 * 落盘位置：`{workspace}/out/resonance_cards/{card.fileName()}`，与 README / Metric 脚本约定一致。
 * 这是工程基建，不含研究判定逻辑；卡片内容（含 qualified）由上游研究内容决定。
 *
 * 设计为一段 [ResearchStage]：输入卡片集合，产出已落盘的文件路径列表，便于下游 Compare/Conclusion 追溯。
 */
class ResonanceCardWriter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) : ResearchStage<List<ResonanceCard>, List<Path>> {

    override fun run(ctx: ResearchContext, input: List<ResonanceCard>): List<Path> {
        val dir = ctx.resolve("out/resonance_cards/.keep").parent
        Files.createDirectories(dir)
        return input.map { card ->
            val target = dir.resolve(card.fileName())
            Files.writeString(target, json.encodeToString(card))
            target
        }
    }
}

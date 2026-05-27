package org.shiroumi.strategy.research

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 管线骨架端到端自检（T7）。
 *
 * 跑通 Study(FakeStudy) → Output(ResonanceCardWriter)，断言：
 * 1. 卡片落盘到 `out/resonance_cards/`，文件名符合命名约定；
 * 2. 产出的卡片 `qualified=false` —— 等价于 count-resonance-cards.sh 输出 0（骨架不产出真实结论）。
 *
 * 用隔离临时工作区，不污染仓库的 research/sentiment_factor/out。
 */
class SkeletonPipelineTest {

    @Test
    fun `skeleton pipeline lands an unqualified card end to end`() {
        val workspace = Files.createTempDirectory("research-skeleton-")
        val ctx = ResearchContext(
            runId = "skeleton-test",
            startDate = LocalDate(2024, 1, 2),
            endDate = LocalDate(2024, 12, 31),
            workspace = workspace,
        )

        val written = SkeletonPipeline.run(ctx)

        // 1. 落盘成功，路径在 out/resonance_cards 下
        assertEquals(1, written.size, "应落盘 1 张骨架卡片")
        val card = written.single()
        assertTrue(card.exists(), "卡片文件应存在：$card")
        assertTrue(card.toString().contains("out/resonance_cards"), "落盘目录应为 out/resonance_cards")

        // 2. 等价于 Metric 脚本逻辑：扫 qualified=true 的卡片数应为 0
        val cardsDir = workspace.resolve("out/resonance_cards")
        val qualifiedCount = cardsDir.listDirectoryEntries("*.json").count { f ->
            Regex("\"qualified\"\\s*:\\s*true").containsMatchIn(f.readText())
        }
        assertEquals(0, qualifiedCount, "骨架阶段 qualified 卡片数必须为 0")

        // 文件名符合 {factor}__{Y}__h{horizon}__{band}__{state_id}.json 约定
        assertTrue(
            card.fileName.toString().startsWith("FAKE__Y2__h3__F2a__"),
            "文件名不符合命名约定：${card.fileName}",
        )
    }
}

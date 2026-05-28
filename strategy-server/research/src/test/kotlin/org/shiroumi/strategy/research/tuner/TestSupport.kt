package org.shiroumi.strategy.research.tuner

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Files
import java.nio.file.Path

/** 给 tuner 测试用的临时 ResearchContext 工厂；workspace 落到 build/test-tmp 下。 */
fun newTestContext(seed: Long = 42L): ResearchContext {
    val tmp: Path = Files.createTempDirectory("tuner-test-")
    return ResearchContext(
        runId = "test-${System.nanoTime()}",
        startDate = LocalDate(2024, 1, 2),
        endDate = LocalDate(2024, 12, 31),
        workspace = tmp,
        randomSeed = seed,
    )
}

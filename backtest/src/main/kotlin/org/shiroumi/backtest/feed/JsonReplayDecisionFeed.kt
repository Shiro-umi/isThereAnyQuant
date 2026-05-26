package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 从 JSON 文件回放策略决策，供离线调试和回归复现使用。
 */
class JsonReplayDecisionFeed(
    private val path: Path,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    },
) : StrategyDecisionFeed {
    private val delegate: InMemoryDecisionFeed by lazy {
        val decisions = json.decodeFromString<List<StrategyDecision>>(Files.readString(path))
        InMemoryDecisionFeed(decisions)
    }

    override fun decisionsFor(date: LocalDate): List<StrategyDecision> = delegate.decisionsFor(date)
}

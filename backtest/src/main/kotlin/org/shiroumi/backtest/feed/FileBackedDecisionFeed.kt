package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 从 `decisions/{executionDate}.json` 反序列化策略决策序列。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.4.3：
 *  - 文件缺失时返回空列表（允许回测在「可交易但当日策略无决策」的日期继续推进）
 *  - 解析失败抛出原始异常，由 [org.shiroumi.backtest.engine.BacktestScheduler]
 *    的 try-catch 转换为 DailyRunStatus.FAILED
 *  - 文件已读取过的内容会缓存在内存，避免同一回测内重复 IO
 */
class FileBackedDecisionFeed(
    private val decisionsDir: Path,
    private val json: Json = DecisionFileJson,
) : StrategyDecisionFeed {

    private val cache: MutableMap<LocalDate, List<StrategyDecision>> = mutableMapOf()

    override fun decisionsFor(date: LocalDate): List<StrategyDecision> {
        cache[date]?.let { return it }
        val file = decisionsDir.resolve("$date.json")
        val decisions = if (!Files.exists(file)) {
            emptyList()
        } else {
            val text = Files.readString(file)
            val decoded = json.decodeFromString<DecisionFile>(text)
            decoded.decisions
        }
        cache[date] = decisions
        return decisions
    }
}

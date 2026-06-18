package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * Agent 买点价喂入：从独立目录 `{agentEntryDir}/{executionDate}.json` 读取 agent 在信号日 T 盘后
 * 为每只选出股票产出的限价买点，构造 (执行日, 标的) -> 买点价 映射。
 *
 * 业务背景与防未来函数口径：
 *  - 回测口径是「agent 在信号日 T 盘后分析、T+1 开盘按 limitPrice 撮合」。agent 的买点价在信号日 T
 *    一次性产出快照后锁定，回测执行日 T+1 直接复用，不在盘中重新计算，因此不存在未来函数。
 *  - 文件命名 `{executionDate}.json`（执行日，即 T+1 开盘日），与 [DecisionFileExporter] 输出口径一致，
 *    便于 [org.shiroumi.backtest.engine.EntryGatekeeper] 按执行日直查买点价。
 *
 * 数据来源与解析口径：
 *  - 复用 [DecisionFileJson] 反序列化 [DecisionFile]，只采纳 [StrategyDecision.TradeIntentDecision]
 *    中 side=BUY 且 hint=LIMIT 且 limitPrice 非空的条目，作为该标的当日的 agent 买点价。
 *  - 同一标的当日多次出现时保留首个买点价（与决策导出去重口径一致）。
 *  - 文件缺失返回空映射（允许「当日 agent 无买点」的执行日继续推进，由闸门按无价分支处理）。
 *  - 解析失败抛出原始异常，交由上层 try-catch 转换为失败状态，不静默吞掉。
 *  - 读取结果按执行日缓存，避免同一回测内重复 IO。
 *
 * 边界纪律：本喂入读取的是与引擎 `decisions/` 完全独立的目录，不读取、不写入引擎决策目录，
 * 仅承载 agent 买点价这一附加事实。
 */
class AgentEntryPriceFeed(
    private val agentEntryDir: Path,
    private val json: Json = DecisionFileJson,
) {

    /** 缓存：执行日 -> (标的 -> agent 买点价)。 */
    private val cache: MutableMap<LocalDate, Map<String, Double>> = mutableMapOf()

    /**
     * 读取某执行日全部 agent 买点价。
     *
     * @param date 执行日（T+1 开盘日）
     * @return 标的 -> 买点价；文件缺失时为空映射
     */
    fun entryPricesFor(date: LocalDate): Map<String, Double> {
        cache[date]?.let { return it }
        val file = agentEntryDir.resolve("$date.json")
        val prices: Map<String, Double> = if (!Files.exists(file)) {
            emptyMap()
        } else {
            val text = Files.readString(file)
            val decoded = json.decodeFromString<DecisionFile>(text)
            val result = LinkedHashMap<String, Double>()
            for (decision in decoded.decisions) {
                if (decision !is StrategyDecision.TradeIntentDecision) continue
                if (decision.side != Side.BUY) continue
                if (decision.hint != ExecutionHint.LIMIT) continue
                val price = decision.limitPrice ?: continue
                result.putIfAbsent(decision.tsCode, price)
            }
            result
        }
        cache[date] = prices
        return prices
    }

    /**
     * 读取某执行日某标的的 agent 买点价。
     *
     * @return 买点价；当日无该标的买点时返回 null（由闸门按无价分支放弃入场）
     */
    fun entryPrice(date: LocalDate, tsCode: String): Double? = entryPricesFor(date)[tsCode]
}

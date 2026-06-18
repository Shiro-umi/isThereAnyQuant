package org.shiroumi.backtest.engine

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.feed.AgentEntryPriceFeed

/**
 * 入场闸门——逐分支复刻生产持仓状态机 `advance` 的入场逻辑。
 *
 * 业务背景：生产侧每日 Top5 只入场 entryPriority（信号日 20 日波动率）最高的 1 只
 * （Top5 全入场实测稀释 4pp 胜率）；持仓由 [PositionExitManager] 按 tp8/u25/H3 多日持有，
 * 当日选股不卖出存量持仓。回测主路径的决策是 [StrategyDecision.TargetPortfolioDecision]
 * （含目标权重），若直接交给 OrderSizer，落选标的会被 rebalance 成 weight=0 → SELL，
 * 破坏「选股只加新仓、离场只由退出规则」的生产语义。
 *
 * 因此闸门做两件事，与生产 advance 对齐：
 * 1. 把当日候选过滤为「未持有 + 按 entryPriority 降序 + 跳空过滤」后的至多 maxDailyEntries 只；
 * 2. 输出显式 BUY [StrategyDecision.TradeIntentDecision]，不再以目标组合驱动卖出，
 *    存量持仓的离场完全交给 [PositionExitManager]。
 *
 * 逐分支对齐生产 advance（HoldingStateMachine）：
 * - maxDailyEntries>0 时按 entryPriority 降序排序；
 * - 逐只尝试：entered.size>=maxDailyEntries 即 break；已持有跳过；开盘<=0 跳过；
 * - 跳空过滤：entryGapMaxPct>0 && signalDateClose>0 && (开盘/signalDateClose-1) > entryGapMaxPct+EPS
 *   → continue（跳过且不占名额，因为是 break-on-count 循环）。
 *
 * maxDailyEntries<=0 时（V3 全入场行为）闸门不改写决策，原样透传。
 *
 * 仓位口径（[fullPositionPerEntry]）：
 * - false（默认）：选中标的沿用选股决策原始权重（目标组合等权切片，单只约 0.2），由 OrderSizer 按该权重折股。
 *   每日入场 1 只 + H3 下大量交易日仅 1~2 只在手 → 大量现金闲置，年化被现金拖低（固定 5 档等权口径）。
 * - true：入场 weight 置为 null → OrderSizer 用 defaultTradeIntentWeight=1.0 单票满仓滚动。
 *   最贴近「每日 1 只」字面执行与研究侧「每笔单票收益率」验收口径；复利最快但单票集中、回撤大。
 *   H3 下偶发并发 2 只时，第 2 只按剩余现金缩放（OrderSizer scaleBuysIfNeeded）。
 *
 * 生产持仓状态机是纯持仓跟踪器（不含现金/数量/权重），不提供仓位 sizing 真值；两种口径都是回测侧建模选择。
 */
class EntryGatekeeper(
    private val config: ExitRulesConfig,
    private val entryPriority: BacktestEntryPriority,
    private val calendar: TradingCalendar,
    /** true = 单票满仓滚动（weight=null→OrderSizer 1.0）；false = 沿用选股原始权重（等权切片）。 */
    private val fullPositionPerEntry: Boolean = false,
    /**
     * 入场排序口径：
     * - VOLATILITY（默认，复刻生产）：按信号日 20 日波动率降序，选波动率最高的至多 maxDailyEntries 只。
     * - MODEL_SCORE：保留候选原始顺序（PreloadedDecisionFeed 已按 model_score 降序），即选模型分最高的至多 N 只。
     */
    private val entryOrdering: EntryOrdering = EntryOrdering.VOLATILITY,
    /**
     * 入场仓位是否按当日实际入场只数等权（各 1/N）。
     * - false（默认）：沿用候选原始权重（选股等权切片，约 0.2/只）或满仓（fullPositionPerEntry=true）。
     * - true：当日入场 N 只时各分配 1/N 仓位（与 maxDailyEntries 配合，如每日 3 只各 1/3）。仅 fullPositionPerEntry=false 时生效。
     */
    private val equalWeightAcrossEntries: Boolean = false,
    /**
     * Agent 买点价喂入。非空时入场口径切换为 agent 限价买点：
     * 选中标的若在该执行日有 agent 买点价 → 输出 hint=LIMIT + limitPrice=agentPrice（T+1 开盘按限价撮合）；
     * 若无买点价 → 放弃该标的入场（不回退 OPEN，记录原因），由 OrderSizer 不生成订单。
     * 用户口径下 agent 对每只选出股票都给价（覆盖率 100%），无价分支仅作防御。
     * 为 null 时保持原 hint=OPEN 行为不变。
     */
    private val agentEntryPrices: AgentEntryPriceFeed? = null,
) {

    /**
     * 对单个交易日的决策应用入场闸门。
     *
     * @param date 执行日（开盘买入日，T+1）
     * @param decisions 当日原始决策（目标组合 / 显式意图混合）
     * @param market 当日行情（取开盘价）
     * @param heldTsCodes 当日盘前已持有的标的（不重复入场）
     * @return 改写后的决策列表
     */
    fun gate(
        date: LocalDate,
        decisions: List<StrategyDecision>,
        market: DailyMarketData,
        heldTsCodes: Set<String>,
    ): List<StrategyDecision> {
        // 不限入场上限：保持 V3 全入场行为，原样透传。
        if (config.maxDailyEntries <= 0) return decisions

        val passthrough = mutableListOf<StrategyDecision>()
        val candidates = mutableListOf<EntryCandidate>()

        for (decision in decisions) {
            when (decision) {
                is StrategyDecision.TargetPortfolioDecision -> {
                    decision.targetWeights.forEach { (tsCode, weight) ->
                        candidates += EntryCandidate(tsCode = tsCode, reason = decision.reason, weight = weight)
                    }
                }
                is StrategyDecision.TradeIntentDecision -> when (decision.side) {
                    // 显式 SELL（如 audit 降级信号 / 退出单）原样透传，不进入入场闸门。
                    Side.SELL -> passthrough += decision
                    Side.BUY -> candidates += EntryCandidate(
                        tsCode = decision.tsCode,
                        reason = decision.reason,
                        weight = decision.weight,
                    )
                }
            }
        }

        if (candidates.isEmpty()) return decisions

        // 同一标的多次出现时合并（保留首个 reason / weight），避免一个 ts_code 占多份候选。
        val dedup = LinkedHashMap<String, EntryCandidate>()
        for (c in candidates) dedup.putIfAbsent(c.tsCode, c)

        // 入场排序口径：MODEL_SCORE 保留候选原始顺序（已按 model_score 降序）；VOLATILITY 按波动率降序。
        val signalDate = previousTradingDate(date)
        val ordered = when (entryOrdering) {
            EntryOrdering.MODEL_SCORE -> dedup.values.toList()
            EntryOrdering.VOLATILITY -> dedup.values
                .map { it to (signalDate?.let { sd -> entryPriority.signalDayVolatility20(it.tsCode, sd) } ?: 0.0) }
                .sortedByDescending { it.second }
                .map { it.first }
        }

        // 先筛出当日实际入场候选（已持有/开盘无效/跳空/无 agent 买点价均跳过且不占名额），
        // 再统一赋权重（等权需先知道 N）。每个候选携带其 agent 买点价（无喂入时为 null）。
        val accepted = mutableListOf<AcceptedEntry>()
        for (candidate in ordered) {
            if (accepted.size >= config.maxDailyEntries) break
            if (candidate.tsCode in heldTsCodes) continue // 已持有，不重复入场
            val open = market.quotes[candidate.tsCode]?.open?.toDouble() ?: continue
            if (open <= 0.0) continue
            // 入场跳空过滤：开盘较信号日收盘跳空超限 → 跳过且不占名额。
            // 信号日 = 执行日上一交易日；预加载 feed 把执行日 preClose 填为上一交易日收盘（即信号日收盘）。
            if (config.entryGapMaxPct > 0.0) {
                val signalClose = market.preClose[candidate.tsCode]
                if (signalClose != null && signalClose > 0.0) {
                    val gap = open / signalClose - 1.0
                    if (gap > config.entryGapMaxPct + EPS) continue
                }
            }
            // Agent 买点价口径：喂入非空时按执行日逐只解析买点价。无价（或非正价）→ 放弃该标的
            // 入场（不回退 OPEN），且不占名额，名额自然让给次优有价候选。
            // 用户口径下 agent 对每只票都给价（覆盖率 100%），无价分支仅作防御，避免凭空回退 OPEN 引入未来函数偏差。
            val agentPrice = if (agentEntryPrices != null) {
                val price = agentEntryPrices.entryPrice(date, candidate.tsCode)
                if (price == null || price <= 0.0) continue
                price
            } else {
                null // 无 agent 喂入：保持原 OPEN 入场行为
            }
            accepted += AcceptedEntry(candidate = candidate, agentPrice = agentPrice)
        }

        // 等权口径：基于「最终入场只数」各分配 1/N（无价票已被剔除，不再占名额）。
        val equalWeight = if (equalWeightAcrossEntries && accepted.isNotEmpty()) 1.0 / accepted.size else null
        val entries = accepted.map { (candidate, agentPrice) ->
            StrategyDecision.TradeIntentDecision(
                effectiveDate = date,
                reason = "入场闸门(maxDailyEntries=${config.maxDailyEntries}): ${candidate.reason}",
                tsCode = candidate.tsCode,
                side = Side.BUY,
                // 满仓口径：weight=null → OrderSizer 用 1.0 单票满仓；
                // 等权跨入场：各 1/N；否则沿用选股原始权重。
                weight = when {
                    fullPositionPerEntry -> null
                    equalWeight != null -> equalWeight
                    else -> candidate.weight
                },
                // 有 agent 买点价 → LIMIT 限价入场（T+1 开盘按限价撮合）；否则沿用 OPEN。
                hint = if (agentPrice != null) ExecutionHint.LIMIT else ExecutionHint.OPEN,
                limitPrice = agentPrice,
            )
        }

        return passthrough + entries
    }

    private fun previousTradingDate(date: LocalDate): LocalDate? {
        val windowStart = date.plus(DatePeriod(days = -14))
        val days = calendar.tradingDays(windowStart, date)
        return if (days.size >= 2) days[days.size - 2] else null
    }

    private data class EntryCandidate(
        val tsCode: String,
        val reason: String,
        val weight: Double?,
    )

    /** 已通过入场闸门的候选 + 其 agent 买点价（无 agent 喂入时为 null，按 OPEN 入场）。 */
    private data class AcceptedEntry(
        val candidate: EntryCandidate,
        val agentPrice: Double?,
    )

    private companion object {
        const val EPS = 1e-6
    }
}

/** 入场排序口径。 */
enum class EntryOrdering {
    /** 按信号日 20 日波动率降序（复刻生产 entryPriority）。 */
    VOLATILITY,

    /** 保留候选原始顺序（PreloadedDecisionFeed 已按 model_score 降序），即按模型分降序。 */
    MODEL_SCORE,
}

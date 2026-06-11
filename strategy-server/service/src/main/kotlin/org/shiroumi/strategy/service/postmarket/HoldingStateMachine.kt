package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState

/**
 * 生产侧持仓状态机——多日持有 + 止盈止损。
 *
 * 业务主权在 strategy-server。规则数值对齐 V3 正式版模型在 2020-06~2026-05 全样本外验证的最优运营点
 * （研究文档 `private/research-docs/v3-honest-model-paper.html`）：
 * - 止盈：当日 HIGH 触及入场价 ×(1 + 5%) → 离场
 * - 时间止损：入场后第 15 个交易日收盘强制离场
 * - 价格止损：默认关闭（验证结论：收盘价止损拦不住跳空深亏，反而消灭深蹲后反弹触达的盈利交易）
 * - 入场跳空过滤：开盘较信号日收盘跳空 > 3% 不入场（跳空利润属于昨日持有者，追入为负期望）
 * - T+1 禁售：入场当日不离场
 *
 * 退出优先级：止盈 > 保盈阶梯 > 时间止损 > 价格止损。
 *
 * v5 快线体系（TP7%/H5，研究文档 `private/research-docs/tp7-h5-feasibility-20260611.html`）通过
 * [ExitRules.profitProtectLadder]（每日保盈档）与 [ExitRules.maxDailyEntries]（每日选5入1，按
 * [EntryCandidate.entryPriority] 波动率优先）开启；两者默认关闭，保持 V3 现行为。
 *
 * 状态机只关心「某只票还持不持」，不引入账户、现金、持仓数量、撮合、缩放。
 * 行情以日 K（前复权优先）判定，由调用方注入，便于单测。
 */
class HoldingStateMachine(
    private val config: ExitRules = ExitRules(),
) {
    /** 每日入场上限是否开启（调用方据此决定是否计算 [EntryCandidate.entryPriority]）。 */
    val entryCapEnabled: Boolean get() = config.maxDailyEntries > 0

    /** 止盈止损与入场规则配置，数值默认对齐 V3 模型全样本外验证最优运营点。 */
    data class ExitRules(
        val takeProfitPct: Double = 0.05,
        val timeStopDays: Int = 15,
        val priceStopEnabled: Boolean = false,
        /** 入场跳空上限：开盘价较信号日收盘价的涨幅超过该值时放弃入场。非正数表示关闭过滤。 */
        val entryGapMaxPct: Double = 0.03,
        /**
         * 保盈阶梯：key = 入场后第 N 个可交易日（daysSinceEntry，1 = 入场次日即首个可卖日），
         * value = 当日 HIGH 触及 入场价 ×(1+value) 即离场的保盈档位。空表示关闭（现行为）。
         * v5 验证最优 H6 档（TP7%/H5 体系）= {1:0.025, 2:0.025, 3:0.025, 4:0.025}。
         */
        val profitProtectLadder: Map<Int, Double> = emptyMap(),
        /**
         * 每日新入场上限：0 表示不限（现行为，目标组合全入场）；
         * v5 验证语义为 1（每日 Top5 仅入场 entryPriority 最高的一只，Top5 全入场实测稀释 4pp 胜率）。
         */
        val maxDailyEntries: Int = 0,
    ) {
        init {
            require(takeProfitPct > 0.0) { "止盈比例必须为正，当前: $takeProfitPct" }
            require(timeStopDays >= 2) { "时间止损天数至少为 2，当前: $timeStopDays" }
            require(maxDailyEntries >= 0) { "每日入场上限不可为负，当前: $maxDailyEntries" }
            profitProtectLadder.forEach { (day, lv) ->
                require(day >= 1 && lv > 0.0) { "保盈阶梯档位非法: day=$day level=$lv" }
            }
        }
    }

    /** 一只新入场标的的元数据。 */
    data class EntryCandidate(
        val tsCode: String,
        /** 信号日（选股日，T 日）最低价。 */
        val signalDateLow: Double,
        /** 信号日（选股日，T 日）收盘价，用于入场跳空过滤；非正数表示缺失（不过滤）。 */
        val signalDateClose: Double = 0.0,
        /**
         * 入场优先级，仅 [ExitRules.maxDailyEntries] > 0 时参与排序（降序，越大越优先）。
         * v5 体系填信号日 20 日对数收益波动率（高波动优先，验证 +1.8pp）。
         */
        val entryPriority: Double = 0.0,
    )

    /** 单日推进结果。 */
    data class DayResult(
        /** 当日收盘后仍在持有的快照（含留存 + 新入场）。 */
        val holdings: List<DailyHoldingState>,
        /** 当日离场（清仓）的 tsCode。 */
        val exited: List<String>,
        /** 当日新入场的 tsCode。 */
        val entered: List<String>,
    )

    /**
     * 推进一个交易日。
     *
     * @param tradeDate 当前交易日（盘后处理日）
     * @param previousHoldings 前一交易日收盘后的持仓状态
     * @param newEntries 当日新入场候选（前一日选股、target_date=tradeDate 的 selected 票）；
     *   入场价取当日开盘价、入场日记为 tradeDate
     * @param tradingDaysSince 计算两个交易日之间的交易日数（不含 start，含 end）
     * @param candleFor 取某 tsCode 在某交易日的日 K；缺失返回 null
     */
    fun advance(
        tradeDate: LocalDate,
        previousHoldings: List<DailyHoldingState>,
        newEntries: List<EntryCandidate>,
        tradingDaysSince: (entryDate: LocalDate, date: LocalDate) -> Int,
        candleFor: (tsCode: String, date: LocalDate) -> Candle?,
    ): DayResult {
        val survivors = mutableListOf<DailyHoldingState>()
        val exited = mutableListOf<String>()
        val previousCodes = previousHoldings.mapTo(mutableSetOf()) { it.tsCode }

        for (holding in previousHoldings) {
            val bar = candleFor(holding.tsCode, tradeDate)
            // 当日无行情（停牌等）：保留持仓不强制处理，顺延到次日判定
            if (bar == null) {
                survivors += holding.copy(tradeDate = tradeDate)
                continue
            }
            if (shouldExit(holding, tradeDate, bar, tradingDaysSince)) {
                exited += holding.tsCode
            } else {
                survivors += holding.copy(tradeDate = tradeDate)
            }
        }

        // 新入场：当日开盘买入，T+1 禁售（当日不参与离场判定）。
        // maxDailyEntries > 0 时按 entryPriority 降序逐一尝试，跳空/缺行情不占名额。
        val entered = mutableListOf<String>()
        val orderedEntries = if (config.maxDailyEntries > 0) {
            newEntries.sortedByDescending { it.entryPriority }
        } else {
            newEntries
        }
        for (candidate in orderedEntries) {
            if (config.maxDailyEntries > 0 && entered.size >= config.maxDailyEntries) break
            if (candidate.tsCode in previousCodes) continue // 已持有，不重复入场
            val bar = candleFor(candidate.tsCode, tradeDate) ?: continue
            val entryPrice = bar.getOpen().toDouble()
            if (entryPrice <= 0.0) continue
            // 入场跳空过滤：开盘较信号日收盘跳空超限 → 放弃入场（跳空利润属于昨日持有者）
            if (config.entryGapMaxPct > 0.0 && candidate.signalDateClose > 0.0) {
                val gap = entryPrice / candidate.signalDateClose - 1.0
                if (gap > config.entryGapMaxPct + EPS) continue
            }
            survivors += DailyHoldingState(
                tradeDate = tradeDate,
                tsCode = candidate.tsCode,
                entryDate = tradeDate,
                entryPrice = entryPrice,
                signalDateLow = candidate.signalDateLow,
            )
            entered += candidate.tsCode
        }

        return DayResult(holdings = survivors, exited = exited, entered = entered)
    }

    private fun shouldExit(
        holding: DailyHoldingState,
        tradeDate: LocalDate,
        bar: Candle,
        tradingDaysSince: (LocalDate, LocalDate) -> Int,
    ): Boolean {
        val daysSinceEntry = tradingDaysSince(holding.entryDate, tradeDate)
        if (daysSinceEntry < 0) return false
        // T+1 禁售：入场当日不离场
        if (daysSinceEntry == 0) return false

        // 优先级 1：止盈
        val tpPrice = holding.entryPrice * (1.0 + config.takeProfitPct)
        if (bar.getHigh().toDouble() + EPS >= tpPrice) return true

        // 优先级 2：保盈阶梯（当日 HIGH 触及入场价 ×(1+档位) 即离场；档位按 daysSinceEntry 查表）
        val ladderLevel = config.profitProtectLadder[daysSinceEntry]
        if (ladderLevel != null && bar.getHigh().toDouble() + EPS >= holding.entryPrice * (1.0 + ladderLevel)) {
            return true
        }

        // 优先级 3：时间止损（入场后第 timeStopDays 个交易日收盘）
        if (daysSinceEntry >= config.timeStopDays - 1) return true

        // 优先级 4：价格止损（收盘 < 信号日最低）
        if (config.priceStopEnabled && daysSinceEntry >= 1) {
            if (bar.getPrice().toDouble() + EPS < holding.signalDateLow) return true
        }
        return false
    }

    private companion object {
        const val EPS = 1e-6
    }
}

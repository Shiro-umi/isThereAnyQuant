package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState

/**
 * 生产侧持仓状态机——多日持有 + 止盈止损。
 *
 * 业务主权在 strategy-server。默认规则 = v5 快线运营点（TP7%/H5/全档 2.5% 保盈阶梯/每日入场 1），
 * 2026-06-12 部署前在生产池（主板+创业板）复验：lw+bce 集成口径（生产包 = 双 loss × 双 seed 四成员）
 * 调参段 76.4%/EV+0.78%、验证段 77.3%/EV+0.89%、240 笔/年、分年全正
 * （研究文档 `private/research-docs/tp7-h5-feasibility-20260611.html`，复验脚本归档于
 * `private/research-docs/tp7-h5/train_v5b_bce_production.py` 头注）：
 * - 止盈：当日 HIGH 触及入场价 ×(1 + 7%) → 离场
 * - 保盈阶梯：入场后第 1~4 个可卖日，当日 HIGH 触及入场价 ×(1 + 2.5%) → 离场
 * - 时间止损：入场后第 5 个交易日收盘强制离场
 * - 每日入场上限 1：Top5 仅入场 [EntryCandidate.entryPriority]（20 日波动率）最高的一只
 * - 价格止损：默认关闭（验证结论：收盘价止损拦不住跳空深亏，反而消灭深蹲后反弹触达的盈利交易）
 * - 入场跳空过滤：开盘较信号日收盘跳空 > 3% 不入场（跳空利润属于昨日持有者，追入为负期望）
 * - T+1 禁售：入场当日不离场
 *
 * 退出优先级：止盈 > 保盈阶梯 > 时间止损 > 价格止损。
 *
 * V3 运营点（TP5%/H15，`private/research-docs/v3-honest-model-paper.html`）为回滚通道，
 * 通过 [ExitRules.V3_OPERATING_POINT] 或系统属性显式覆盖。
 *
 * 状态机只关心「某只票还持不持」，不引入账户、现金、持仓数量、撮合、缩放。
 * 行情以日 K（前复权优先）判定，由调用方注入，便于单测。
 */
class HoldingStateMachine(
    private val config: ExitRules = ExitRules(),
) {
    /** 每日入场上限是否开启（调用方据此决定是否计算 [EntryCandidate.entryPriority]）。 */
    val entryCapEnabled: Boolean get() = config.maxDailyEntries > 0

    /** 止盈止损与入场规则配置，数值默认对齐 v5 快线生产运营点（2026-06-12 实装）。 */
    data class ExitRules(
        val takeProfitPct: Double = 0.07,
        val timeStopDays: Int = 5,
        val priceStopEnabled: Boolean = false,
        /** 入场跳空上限：开盘价较信号日收盘价的涨幅超过该值时放弃入场。非正数表示关闭过滤。 */
        val entryGapMaxPct: Double = 0.03,
        /**
         * 保盈阶梯：key = 入场后第 N 个可交易日（daysSinceEntry，1 = 入场次日即首个可卖日），
         * value = 当日 HIGH 触及 入场价 ×(1+value) 即离场的保盈档位。空表示关闭。
         * 默认 = v5 快线全档 2.5%（H5 周期内 1~4 日，第 5 日为时间止损日）。
         */
        val profitProtectLadder: Map<Int, Double> = mapOf(1 to 0.025, 2 to 0.025, 3 to 0.025, 4 to 0.025),
        /**
         * 每日新入场上限：默认 1（每日 Top5 仅入场 entryPriority 最高的一只，
         * Top5 全入场实测稀释 4pp 胜率）；0 表示不限（V3 行为，目标组合全入场）。
         */
        val maxDailyEntries: Int = 1,
    ) {
        companion object {
            /** V3 正式版运营点（TP5%/H15/无阶梯/全入场）——回滚通道。 */
            val V3_OPERATING_POINT = ExitRules(
                takeProfitPct = 0.05,
                timeStopDays = 15,
                profitProtectLadder = emptyMap(),
                maxDailyEntries = 0,
            )

            /**
             * 从系统属性装配持仓规则；全部缺省时与 `ExitRules()` 默认值一致
             * （v5 快线运营点：TP7%/H5/全档 2.5% 阶梯/每日入场 1，2026-06-12 实装为生产默认）。
             * 盘后状态机推进与持仓跟踪展示链路共用同一装配入口，保证两侧规则口径一致。
             *
             * 回滚到 [V3_OPERATING_POINT] 的覆盖示例：
             * -Dquant.strategy.holding.takeProfitPct=0.05
             * -Dquant.strategy.holding.timeStopDays=15
             * -Dquant.strategy.holding.profitProtectLadder=    （空值 = 关闭阶梯）
             * -Dquant.strategy.holding.maxDailyEntries=0
             */
            fun fromSystemProperties(): ExitRules {
                val defaults = ExitRules()
                val ladder = System.getProperty("quant.strategy.holding.profitProtectLadder")
                    ?.split(',')
                    ?.filter { it.isNotBlank() }
                    ?.associate { entry ->
                        val (day, level) = entry.split(':', limit = 2)
                        day.trim().toInt() to level.trim().toDouble()
                    }
                    ?: defaults.profitProtectLadder
                return ExitRules(
                    takeProfitPct = System.getProperty("quant.strategy.holding.takeProfitPct")
                        ?.toDouble() ?: defaults.takeProfitPct,
                    timeStopDays = System.getProperty("quant.strategy.holding.timeStopDays")
                        ?.toInt() ?: defaults.timeStopDays,
                    priceStopEnabled = System.getProperty("quant.strategy.holding.priceStopEnabled")
                        ?.toBooleanStrictOrNull() ?: defaults.priceStopEnabled,
                    entryGapMaxPct = System.getProperty("quant.strategy.holding.entryGapMaxPct")
                        ?.toDouble() ?: defaults.entryGapMaxPct,
                    profitProtectLadder = ladder,
                    maxDailyEntries = System.getProperty("quant.strategy.holding.maxDailyEntries")
                        ?.toInt() ?: defaults.maxDailyEntries,
                )
            }
        }

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

    /** 离场原因，按退出优先级排列。 */
    enum class ExitReason {
        TAKE_PROFIT,
        PROFIT_PROTECT,
        TIME_STOP,
        PRICE_STOP,
    }

    /**
     * 离场判决：原因 + 规则口径离场价。
     * 触价类（止盈/保盈）的离场价 = max(当日开盘, 触发价)——高开直接越过触发价时按开盘价成交；
     * 收盘类（时间止损/价格止损）的离场价 = 当日收盘价。
     */
    data class ExitVerdict(
        val reason: ExitReason,
        val exitPrice: Double,
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
            if (evaluateExit(holding, tradeDate, bar, tradingDaysSince) != null) {
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

    /**
     * 离场判决。null = 当日不离场。
     *
     * 盘后状态机推进与持仓跟踪展示链路共用此判定，保证「该不该走」与「按什么价走、为什么走」
     * 出自同一规则源。注意展示链路重建历史判决时，行情口径与交易日计数必须与生产推进一致。
     */
    fun evaluateExit(
        holding: DailyHoldingState,
        tradeDate: LocalDate,
        bar: Candle,
        tradingDaysSince: (LocalDate, LocalDate) -> Int,
    ): ExitVerdict? {
        val daysSinceEntry = tradingDaysSince(holding.entryDate, tradeDate)
        if (daysSinceEntry < 0) return null
        // T+1 禁售：入场当日不离场
        if (daysSinceEntry == 0) return null

        val open = bar.getOpen().toDouble()

        // 优先级 1：止盈
        val tpPrice = holding.entryPrice * (1.0 + config.takeProfitPct)
        if (bar.getHigh().toDouble() + EPS >= tpPrice) {
            return ExitVerdict(ExitReason.TAKE_PROFIT, maxOf(open, tpPrice))
        }

        // 优先级 2：保盈阶梯（当日 HIGH 触及入场价 ×(1+档位) 即离场；档位按 daysSinceEntry 查表）
        val ladderLevel = config.profitProtectLadder[daysSinceEntry]
        if (ladderLevel != null) {
            val ladderPrice = holding.entryPrice * (1.0 + ladderLevel)
            if (bar.getHigh().toDouble() + EPS >= ladderPrice) {
                return ExitVerdict(ExitReason.PROFIT_PROTECT, maxOf(open, ladderPrice))
            }
        }

        // 优先级 3：时间止损（入场后第 timeStopDays 个交易日收盘）
        if (daysSinceEntry >= config.timeStopDays - 1) {
            return ExitVerdict(ExitReason.TIME_STOP, bar.getPrice().toDouble())
        }

        // 优先级 4：价格止损（收盘 < 信号日最低）
        if (config.priceStopEnabled && daysSinceEntry >= 1) {
            if (bar.getPrice().toDouble() + EPS < holding.signalDateLow) {
                return ExitVerdict(ExitReason.PRICE_STOP, bar.getPrice().toDouble())
            }
        }
        return null
    }

    private companion object {
        const val EPS = 1e-6
    }
}

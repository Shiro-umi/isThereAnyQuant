package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState

/**
 * 生产侧持仓状态机——多日持有 + 止盈止损。
 *
 * 业务主权在 strategy-server。默认规则 = tp8/H3/3 只运营点（基于回测：每日 3 只模型分 + 各 1/N 等权 +
 * 无浅止损，2022~2026 区间最大回撤 -56%→-34%、夏普 1.84→2.32 优于单票口径）：
 * - 止盈：当日 HIGH 触及入场价 ×(1 + 8%) → 离场
 * - 保盈阶梯：入场后第 1~2 个可卖日，当日 HIGH 触及入场价 ×(1 + 2.5%) → 离场
 * - 浅浮亏止损：默认关闭（3 只分散口径下去掉浅止损年化与夏普更优；分散本身已控尾部）
 * - 时间止损：入场后第 3 个交易日收盘强制离场
 * - 每日入场上限 3：Top5 按 [EntryCandidate.entryPriority]（模型分）降序入场前 3 只
 * - 价格止损：默认关闭（验证结论：收盘价跌破信号日低点止损拦不住跳空深亏，反而消灭深蹲后反弹触达的盈利交易）
 * - 入场跳空过滤：开盘较信号日收盘跳空 > 3% 不入场（跳空利润属于昨日持有者，追入为负期望）
 * - T+1 禁售：入场当日不离场
 *
 * 退出优先级：止盈 > 保盈阶梯 > 浅浮亏止损 > 时间止损 > 价格止损。
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

    /** 当前生效的止盈止损规则（只读视图），供展示链路推导「下一个可执行卖点」。 */
    val rules: ExitRules get() = config

    /**
     * 止盈止损与入场规则配置，数值默认对齐 tp8/u25/H3 生产运营点（2026-06-13 实装）。
     * 选点依据：exit-policy 研究终局（私有仓 exit-policy-paper-20260613.html）——
     * 生产语义重放下对 tp7/u25/H5 旧运营点 EV +0.12pp、巨亏 -43%（分年全降）、
     * 对 tp7/u25/H3 分年 7/7 全升且分板块双升；止盈档位只动赢家上行，尾部由阶梯+H3 持有。
     */
    data class ExitRules(
        val takeProfitPct: Double = 0.08,
        val timeStopDays: Int = 3,
        val priceStopEnabled: Boolean = false,
        /**
         * 浅浮亏止损线：入场后到期前，当日收盘价跌破 入场价 ×(1 + value) 即当日收盘离场。
         * value 为负数（如 -0.03）表示开启；0 或正数表示关闭。
         * 默认 = 0.0（关闭）：3 只分散口径下回测显示去掉浅止损年化与夏普更优
         * （分散本身已控尾部，浅止损反而砍掉深蹲后反弹的盈利交易）。
         * 历史 -0.03 档（单票口径下把 H3 到期巨亏率 2.77%→1.04%，EV 中性）见 temp/probe_shallow_loss_exit_v5.py。
         */
        val shallowStopLossPct: Double = 0.0,
        /** 入场跳空上限：开盘价较信号日收盘价的涨幅超过该值时放弃入场。非正数表示关闭过滤。 */
        val entryGapMaxPct: Double = 0.03,
        /**
         * 保盈阶梯：key = 入场后第 N 个可交易日（daysSinceEntry，1 = 入场次日即首个可卖日），
         * value = 当日 HIGH 触及 入场价 ×(1+value) 即离场的保盈档位。空表示关闭。
         * 默认 = 全档 2.5%（H3 周期内 1~2 日，第 3 日为时间止损日）。
         */
        val profitProtectLadder: Map<Int, Double> = mapOf(1 to 0.025, 2 to 0.025),
        /**
         * 每日新入场上限：默认 3（每日按 entryPriority 降序入场前 3 只，各 1/N 等权）；
         * 0 表示不限（目标组合全入场）。入场排序由 entryPriority 决定（生产注入 model_score → 模型分降序）。
         * 历史单票口径 = 1（Top5 仅入波动率最高 1 只）；3 只分散口径回测最大回撤 -56%→-34%、夏普 1.84→2.32。
         */
        val maxDailyEntries: Int = 3,
    ) {
        companion object {
            /** V3 正式版运营点（TP5%/H15/无阶梯/全入场/无浅止损）——历史回滚通道。 */
            val V3_OPERATING_POINT = ExitRules(
                takeProfitPct = 0.05,
                timeStopDays = 15,
                shallowStopLossPct = 0.0,
                profitProtectLadder = emptyMap(),
                maxDailyEntries = 0,
            )

            /**
             * v5 快线旧运营点（TP7%/H5/全档 2.5% 阶梯/每日入场 1/无浅止损，2026-06-12~13 生产默认）。
             * 用途：①回滚通道；②盘后影子对照基准（与现行 tp8/H3+浅止损双判决对比，验证切换增益在实盘复现）。
             */
            val V5_FAST_TP7_H5 = ExitRules(
                takeProfitPct = 0.07,
                timeStopDays = 5,
                shallowStopLossPct = 0.0,
                profitProtectLadder = mapOf(1 to 0.025, 2 to 0.025, 3 to 0.025, 4 to 0.025),
                maxDailyEntries = 1,
            )

            /**
             * 从系统属性装配持仓规则；全部缺省时与 `ExitRules()` 默认值一致
             * （tp8/H3/3 只运营点：TP8%/H3/1~2 日 2.5% 阶梯/无浅止损/每日入场 3 只模型分降序）。
             * 盘后状态机推进与持仓跟踪展示链路共用同一装配入口，保证两侧规则口径一致。
             *
             * 回滚到 [V5_FAST_TP7_H5] 的覆盖示例：
             * -Dquant.strategy.holding.takeProfitPct=0.07
             * -Dquant.strategy.holding.timeStopDays=5
             * -Dquant.strategy.holding.profitProtectLadder=1:0.025,2:0.025,3:0.025,4:0.025
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
                    shallowStopLossPct = System.getProperty("quant.strategy.holding.shallowStopLossPct")
                        ?.toDouble() ?: defaults.shallowStopLossPct,
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
         * 生产填模型分（selection.modelScore），即每日入场模型分降序的前 maxDailyEntries 只。
         */
        val entryPriority: Double = 0.0,
        /**
         * 破位加分标记：该票在信号日 T 前 LOOKBACK 个交易日窗内出现回归通道破位事件。
         * true = 破位票，入场排序排到所有非破位票之前（破位 = 高分票被超跌、非被追高，前置以减巨亏）。
         * 仅当开关 quant.strategy.holding.breakdownRerank=true 时由 [BreakdownRerankService] 置 true；
         * 开关关闭（默认）时恒为 false，入场排序行为与历史一致（纯模型分降序）。
         */
        val breakdownFlag: Boolean = false,
        /**
         * Agent 量价买点限价（QFQ 口径，与信号日 K 线同标系）；null = 无买点。
         *
         * 有值时入场走 LIMIT 触达语义，与回测撮合 [org.shiroumi.backtest.match.LimitOrderMatching] 完全对齐：
         * 当日 QFQ 最低价触达限价（low <= limit + 容差）才入场，成交价 = min(开盘, 限价)；未触达当日不入场、
         * 不占当日入场名额，由后续交易日重新判定。null 时回退原开盘价无条件建仓（保留历史口径，
         * 也是 agent 分析失败/缺买点票的兜底）。
         */
        val entryLimitPrice: Double? = null,
    )

    /** 离场原因，按退出优先级排列。 */
    enum class ExitReason {
        TAKE_PROFIT,
        PROFIT_PROTECT,
        SHALLOW_STOP,
        TIME_STOP,
        PRICE_STOP,
    }

    /**
     * 离场判决：原因 + 规则口径离场价。
     * 触价类（止盈/保盈）的离场价 = max(当日开盘, 触发价)——高开直接越过触发价时按开盘价成交；
     * 收盘类（浅浮亏止损/时间止损/价格止损）的离场价 = 当日收盘价。
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
     *   入场价：有 [EntryCandidate.entryLimitPrice] 走 LIMIT 触达语义（触达取 min(开盘,限价)、未触达当日不入场），
     *   否则取当日开盘价无条件建仓；入场日记为 tradeDate
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
        // maxDailyEntries > 0 时按 [破位标记优先, 模型分降序] 逐一尝试，跳空/缺行情不占名额。
        // 破位票（breakdownFlag=true）排到所有非破位票之前；破位组内、非破位组内均仍按 entryPriority（模型分）降序。
        // breakdownFlag 仅在 quant.strategy.holding.breakdownRerank=true 时可能为 true；关闭时全 false，
        // compareByDescending{breakdownFlag} 对全 false 退化为恒等，排序结果 = 纯模型分降序（行为同历史）。
        val entered = mutableListOf<String>()
        val orderedEntries = if (config.maxDailyEntries > 0) {
            newEntries.sortedWith(
                compareByDescending<EntryCandidate> { it.breakdownFlag }
                    .thenByDescending { it.entryPriority }
            )
        } else {
            newEntries
        }
        for (candidate in orderedEntries) {
            if (config.maxDailyEntries > 0 && entered.size >= config.maxDailyEntries) break
            if (candidate.tsCode in previousCodes) continue // 已持有，不重复入场
            val bar = candleFor(candidate.tsCode, tradeDate) ?: continue
            val open = bar.getOpen().toDouble()
            if (open <= 0.0) continue
            // 入场跳空过滤：开盘较信号日收盘跳空超限 → 放弃入场（跳空利润属于昨日持有者）
            if (config.entryGapMaxPct > 0.0 && candidate.signalDateClose > 0.0) {
                val gap = open / candidate.signalDateClose - 1.0
                if (gap > config.entryGapMaxPct + EPS) continue
            }
            // 入场成交价：有 agent 买点 → LIMIT 触达语义（与回测 LimitOrderMatching 同口径）；否则开盘价无条件建仓。
            // 未触达返回 null，当日放弃入场且不占名额，由后续交易日重新判定。
            val entryPrice = resolveEntryPrice(candidate.entryLimitPrice, open, bar.getLow().toDouble())
                ?: continue
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

        // 优先级 3：浅浮亏止损（到期前，当日收盘跌破入场价 ×(1+shallowStopLossPct) → 当日收盘离场）
        // 收盘判定避开日内假摔；只在止盈/保盈未触发后生效，故不会错杀当日冲高回落但仍盈利的票。
        if (config.shallowStopLossPct < 0.0 && daysSinceEntry < config.timeStopDays - 1) {
            val stopPrice = holding.entryPrice * (1.0 + config.shallowStopLossPct)
            if (bar.getPrice().toDouble() < stopPrice - EPS) {
                return ExitVerdict(ExitReason.SHALLOW_STOP, bar.getPrice().toDouble())
            }
        }

        // 优先级 4：时间止损（入场后第 timeStopDays 个交易日收盘）
        if (daysSinceEntry >= config.timeStopDays - 1) {
            return ExitVerdict(ExitReason.TIME_STOP, bar.getPrice().toDouble())
        }

        // 优先级 5：价格止损（收盘 < 信号日最低）
        if (config.priceStopEnabled && daysSinceEntry >= 1) {
            if (bar.getPrice().toDouble() + EPS < holding.signalDateLow) {
                return ExitVerdict(ExitReason.PRICE_STOP, bar.getPrice().toDouble())
            }
        }
        return null
    }

    /**
     * 计算一只票当日的入场成交价。
     *
     * - [limit] 为 null：无 agent 买点，回退开盘价无条件建仓（历史口径）。
     * - [limit] 有值：LIMIT 触达语义，与回测 [org.shiroumi.backtest.match.LimitOrderMatching] BUY 分支一字对齐：
     *   当日最低价高于限价（超出 [TOUCH_EPS] 容差）→ 未触达，返回 null（当日放弃入场）；
     *   触达则成交价 = min(开盘, 限价)，不突破限价约束。
     *
     * @return 入场成交价；未触达限价返回 null
     */
    private fun resolveEntryPrice(limit: Double?, open: Double, low: Double): Double? {
        if (limit == null || limit <= 0.0) return open
        if (low > limit + TOUCH_EPS) return null
        return minOf(open, limit)
    }

    private companion object {
        const val EPS = 1e-6

        /**
         * 限价触达判定容差，与回测 [org.shiroumi.backtest.match.LimitOrderMatching.TOUCH_EPS] 同值。
         * QFQ 换算后的 low/limit 为 Float，`low == limit` 的精确触及会因二进制误差被误判为未触及；
         * 1e-4 远小于最小报价单位 0.01，吸收换算误差而不放入真正未触及（差 ≥0.01）的单。
         */
        const val TOUCH_EPS = 1e-4
    }
}

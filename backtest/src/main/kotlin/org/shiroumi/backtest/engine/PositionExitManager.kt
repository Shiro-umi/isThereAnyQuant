package org.shiroumi.backtest.engine

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 持仓退出规则配置，逐分支复刻生产持仓状态机 tp8/u25/H3 运营点
 * （`strategy-server/service/.../postmarket/HoldingStateMachine.ExitRules`，2026-06-13 实装默认）。
 *
 * 边界约束：backtest 严禁依赖 `:strategy-server:service`，本配置只复刻数值与分支语义，不 import 生产类型。
 *
 * 退出优先级（逐行对齐生产 evaluateExit）：
 *  1. T+1 禁售：入场当日不离场
 *  2. 止盈：HIGH 触及入场价 ×(1+takeProfitPct) → 离场价 = max(开盘, 触发价)
 *  3. 保盈阶梯：profitProtectLadder[daysSinceEntry] 命中且 HIGH 触及入场价 ×(1+档位) → max(开盘, 触发价)
 *  4. 浅浮亏止损：shallowStopLossPct<0 && daysSinceEntry<timeStopDays-1 && 收盘 < 入场价 ×(1+shallowStopLossPct) → 收盘离场
 *  5. 时间止损：daysSinceEntry>=timeStopDays-1 → 收盘离场
 *  6. 价格止损：priceStopEnabled && daysSinceEntry>=1 && 收盘 < 信号日最低 → 收盘离场
 *
 * 触价类（止盈/保盈）离场价取 max(开盘, 触发价)（高开穿越按开盘成交）；收盘类离场价 = 当日收盘。
 */
data class ExitRulesConfig(
    /** 止盈比例（从入场价算起），默认 0.08（生产 tp8）。 */
    val takeProfitPct: Double = 0.08,
    /** 时间止损天数（入场后第 timeStopDays 个交易日收盘强平）。默认 3（H3）。 */
    val timeStopDays: Int = 3,
    /** 是否启用价格止损（收盘跌破信号日最低）。默认 false（生产关闭）。 */
    val priceStopEnabled: Boolean = false,
    /** T+1 禁止卖出（入场日不可卖出）。 */
    val t1NoSell: Boolean = true,
    /**
     * 浅浮亏止损线：入场后到期前（daysSinceEntry < timeStopDays-1），当日收盘价跌破
     * 入场价 ×(1+value) 即当日收盘离场。value 为负数（如 -0.03）表示开启；0 或正数表示关闭。
     */
    val shallowStopLossPct: Double = -0.03,
    /**
     * 保盈阶梯：key = 入场后第 N 个可交易日（daysSinceEntry，1 = 入场次日即首个可卖日），
     * value = 当日 HIGH 触及 入场价 ×(1+value) 即离场的保盈档位。空表示关闭。
     * 默认 = 1~2 日各 2.5%（H3 周期内，第 3 日为时间止损日）。
     */
    val profitProtectLadder: Map<Int, Double> = mapOf(1 to 0.025, 2 to 0.025),
    /** 入场跳空上限：开盘价较信号日收盘价的涨幅超过该值时放弃入场。非正数表示关闭过滤。 */
    val entryGapMaxPct: Double = 0.03,
    /** 每日新入场上限：默认 1（每日 Top5 仅入场 entryPriority 最高的一只）；0 表示不限。 */
    val maxDailyEntries: Int = 1,
) {
    init {
        require(takeProfitPct > 0.0) { "止盈比例必须为正，当前: $takeProfitPct" }
        require(timeStopDays >= 2) { "时间止损天数至少为 2，当前: $timeStopDays" }
        require(maxDailyEntries >= 0) { "每日入场上限不可为负，当前: $maxDailyEntries" }
        profitProtectLadder.forEach { (day, lv) ->
            require(day >= 1 && lv > 0.0) { "保盈阶梯档位非法: day=$day level=$lv" }
        }
    }

    companion object {
        /**
         * tp8/u25/H3 生产运营点（TP8%/H3/1~2 日 2.5% 阶梯/浅止损 -3%/每日入场 1，2026-06-13 实装默认）。
         * 与无参 [ExitRulesConfig] 默认值一致，提供具名常量便于 CLI 一键套用。
         */
        val TP8_H3 = ExitRulesConfig()

        /**
         * tp8/u25/H3 但关闭浅浮亏止损（shallowStopLossPct=0），用于对照不含浅止损的左尾/EV 影响。
         */
        val TP8_H3_NO_SHALLOW = ExitRulesConfig(shallowStopLossPct = 0.0)
    }
}

/**
 * 持仓退出管理器：按交易规则自动监控持仓并生成退出决策。
 *
 * 职责明确：
 * - 记录每笔持仓的入场元数据（日期、价格、信号日最低价、信号日收盘价）
 * - 每日盘前检查所有持仓是否满足退出条件
 * - 生成 [StrategyDecision.TradeIntentDecision] 退出决策，由 [BacktestScheduler] 统一执行
 *
 * 退出优先级（对齐生产 HoldingStateMachine.evaluateExit）：
 * 止盈 > 保盈阶梯 > 浅浮亏止损 > 时间止损 > 价格止损。
 */
class PositionExitManager(
    private val config: ExitRulesConfig,
    private val calendar: TradingCalendar,
    private val marketDataFeed: BacktestMarketDataFeed,
) {

    /**
     * 入场元数据——每笔持仓的上下文信息，用于退出条件判定。
     */
    data class EntryMeta(
        /** 入场日（T+1，即实际买入日）。 */
        val entryDate: LocalDate,
        /** 实际买入价（成交价，已含滑点）。 */
        val entryPrice: Double,
        /** 信号日最低价（T 日 low），用于价格止损判定。 */
        val signalDateLow: Double,
        /** 信号日收盘价（T 日 close），仅用于入场闸门复用（退出判定不依赖）。 */
        val signalDateClose: Double,
    )

    /** tsCode → 入场元数据。仅跟踪当前持仓。 */
    private val activeMeta: MutableMap<String, EntryMeta> = mutableMapOf()

    // ---- 公开接口 ----

    /** 当前跟踪的持仓数量。 */
    fun trackedCount(): Int = activeMeta.size

    /**
     * 在撮合成交后调用：记录新买入的入场元数据。
     *
     * @param tsCode 股票代码
     * @param entryDate 入场日
     * @param entryPrice 实际成交价
     */
    fun onEntry(tsCode: String, entryDate: LocalDate, entryPrice: Double) {
        // 查找信号日（入场日的前一个交易日，即 T 日）
        val signalDate = previousTradingDate(entryDate) ?: entryDate
        // 从行情 feed 获取 T 日最低价 / 收盘价
        val signalBar = marketDataFeed.marketDataFor(signalDate).quotes[tsCode]
        val signalLow = signalBar?.low?.toDouble() ?: entryPrice
        val signalClose = signalBar?.close?.toDouble() ?: 0.0
        activeMeta[tsCode] = EntryMeta(
            entryDate = entryDate,
            entryPrice = entryPrice,
            signalDateLow = signalLow,
            signalDateClose = signalClose,
        )
    }

    /**
     * 在持仓完全清空后调用：清除入场元数据。
     */
    fun onExit(tsCode: String) {
        activeMeta.remove(tsCode)
    }

    /**
     * 盘前检查：对当前全部持仓逐一判定是否触发退出条件。
     *
     * 注意：本方法不修改 [activeMeta]（清除在 [onExit] 中由调度器在成交后调用）。
     *
     * @param date 当前交易日
     * @param positions 当前持仓快照（key = tsCode, value = 持仓信息）
     * @return 触发的退出决策列表（可能为空）
     */
    fun checkExits(
        date: LocalDate,
        positions: Map<String, StockPosition>,
    ): List<StrategyDecision.TradeIntentDecision> {
        val exits = mutableListOf<StrategyDecision.TradeIntentDecision>()
        val market = marketDataFeed.marketDataFor(date)

        for ((tsCode, position) in positions) {
            if (position.totalQty <= 0L) continue
            val meta = activeMeta[tsCode] ?: continue
            val bar = market.quotes[tsCode] ?: continue

            val daysSinceEntry = tradingDaysSince(meta.entryDate, date)
            if (daysSinceEntry < 0) continue // 不应该发生

            // T+1 禁售：入场日不可卖出
            if (config.t1NoSell && daysSinceEntry == 0) continue

            val open = bar.open.toDouble()
            val high = bar.high.toDouble()
            val close = bar.close.toDouble()

            // 优先级 1：止盈 — HIGH 触及入场价 ×(1+takeProfitPct)；离场价 = max(开盘, 触发价)
            val tpPrice = meta.entryPrice * (1.0 + config.takeProfitPct)
            if (high + EPS >= tpPrice) {
                val exitPrice = maxOf(open, tpPrice)
                exits += exitOrder(
                    date = date,
                    tsCode = tsCode,
                    reason = "止盈: HIGH ${bar.high} 触及 +${pct(config.takeProfitPct)} (离场价 ${fmt(exitPrice)})",
                    hint = ExecutionHint.LIMIT,
                    limitPrice = exitPrice,
                )
                continue
            }

            // 优先级 2：保盈阶梯 — 档位按 daysSinceEntry 查表；HIGH 触及 → max(开盘, 触发价)
            val ladderLevel = config.profitProtectLadder[daysSinceEntry]
            if (ladderLevel != null) {
                val ladderPrice = meta.entryPrice * (1.0 + ladderLevel)
                if (high + EPS >= ladderPrice) {
                    val exitPrice = maxOf(open, ladderPrice)
                    exits += exitOrder(
                        date = date,
                        tsCode = tsCode,
                        reason = "保盈阶梯: 第 ${daysSinceEntry} 个可卖日 HIGH ${bar.high} 触及 +${pct(ladderLevel)} (离场价 ${fmt(exitPrice)})",
                        hint = ExecutionHint.LIMIT,
                        limitPrice = exitPrice,
                    )
                    continue
                }
            }

            // 优先级 3：浅浮亏止损 — 到期前收盘跌破 入场价 ×(1+shallowStopLossPct) → 当日收盘离场
            if (config.shallowStopLossPct < 0.0 && daysSinceEntry < config.timeStopDays - 1) {
                val stopPrice = meta.entryPrice * (1.0 + config.shallowStopLossPct)
                if (close < stopPrice - EPS) {
                    exits += exitOrder(
                        date = date,
                        tsCode = tsCode,
                        reason = "浅浮亏止损: 收盘 ${bar.close} < 入场价 ×(1${pct(config.shallowStopLossPct)}) (${fmt(stopPrice)})，第 ${daysSinceEntry} 个可卖日",
                        hint = ExecutionHint.CLOSE,
                    )
                    continue
                }
            }

            // 优先级 4：时间止损 — 入场后第 timeStopDays 个交易日收盘强制清仓
            if (daysSinceEntry >= config.timeStopDays - 1) {
                exits += exitOrder(
                    date = date,
                    tsCode = tsCode,
                    reason = "时间止损: 入场后第 ${daysSinceEntry + 1} 个交易日（T+${config.timeStopDays}），收盘 ${bar.close}",
                    hint = ExecutionHint.CLOSE,
                )
                continue
            }

            // 优先级 5：价格止损 — 收盘 < 信号日最低
            if (config.priceStopEnabled && daysSinceEntry >= 1) {
                if (close + EPS < meta.signalDateLow) {
                    exits += exitOrder(
                        date = date,
                        tsCode = tsCode,
                        reason = "价格止损: 收盘 ${bar.close} < 信号日最低 ${meta.signalDateLow}（入场后第 ${daysSinceEntry + 1} 日）",
                        hint = ExecutionHint.CLOSE,
                    )
                    continue
                }
            }
        }

        return exits
    }

    // ---- 内部辅助 ----

    private fun exitOrder(
        date: LocalDate,
        tsCode: String,
        reason: String,
        hint: ExecutionHint,
        limitPrice: Double? = null,
    ): StrategyDecision.TradeIntentDecision = StrategyDecision.TradeIntentDecision(
        effectiveDate = date,
        reason = reason,
        tsCode = tsCode,
        side = Side.SELL,
        hint = hint,
        limitPrice = limitPrice,
    )

    /**
     * 计算两个交易日之间的交易日数（不含 start，含 end）。
     * 例如：entry=T+1, date=T+2 → 1；entry=T+1, date=T+3 → 2。
     */
    private fun tradingDaysSince(startDate: LocalDate, endDate: LocalDate): Int {
        if (endDate <= startDate) return 0
        // 使用 calendar 获取区间内的交易日
        val days = calendar.tradingDays(startDate, endDate)
        // days 包含 startDate（如果它是交易日），所以 count - 1
        return (days.size - 1).coerceAtLeast(0)
    }

    private fun previousTradingDate(date: LocalDate): LocalDate? {
        // 回溯足够多的自然日，覆盖长假期场景
        val windowStart = date.plus(DatePeriod(days = -14))
        val days = calendar.tradingDays(windowStart, date)
        // 最后一个元素是 date 本身（如果是交易日），倒数第二个是前一个交易日
        return if (days.size >= 2) days[days.size - 2] else null
    }

    private companion object {
        const val EPS = 1e-6

        fun pct(v: Double): String {
            val rounded = kotlin.math.round(v * 10000.0) / 100.0
            return "${rounded}%"
        }

        fun fmt(v: Double): String {
            val rounded = kotlin.math.round(v * 1000.0) / 1000.0
            return rounded.toString()
        }
    }
}

package org.shiroumi.backtest.engine

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition
import org.shiroumi.backtest.domain.StrategyDecision
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 持仓退出规则配置，对齐用户指定的交易规则：
 *
 * - 止盈：+7% 立即清仓（以 HIGH 价触发，LIMIT 执行）
 * - 时间止损：T+3 收盘未止盈则强制清仓（CLOSE 执行）
 * - 价格止损：T+2 / T+3 收盘价 < T 日最低点（CLOSE 执行）
 * - T+1 禁售：入场当日不可卖出
 */
data class ExitRulesConfig(
    /** 止盈比例（从入场价算起），默认 0.07。 */
    val takeProfitPct: Double = 0.07,
    /** 时间止损天数（从入场日算起，含入场日）。默认 3 即 T+3 收盘强制清仓。 */
    val timeStopDays: Int = 3,
    /** 是否启用价格止损。 */
    val priceStopEnabled: Boolean = true,
    /** T+1 禁止卖出（入场日不可卖出）。 */
    val t1NoSell: Boolean = true,
) {
    init {
        require(takeProfitPct > 0.0) { "止盈比例必须为正，当前: $takeProfitPct" }
        require(timeStopDays >= 2) { "时间止损天数至少为 2，当前: $timeStopDays" }
    }
}

/**
 * 持仓退出管理器：按交易规则自动监控持仓并生成退出决策。
 *
 * 职责明确：
 * - 记录每笔持仓的入场元数据（日期、价格、信号日最低价）
 * - 每日盘前检查所有持仓是否满足退出条件
 * - 生成 [StrategyDecision.TradeIntentDecision] 退出决策，由 [BacktestScheduler] 统一执行
 *
 * 退出优先级：止盈 > 时间止损 > 价格止损
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
        // 从行情 feed 获取 T 日最低价
        val signalLow = marketDataFeed.marketDataFor(signalDate)
            .quotes[tsCode]?.low?.toDouble() ?: entryPrice
        activeMeta[tsCode] = EntryMeta(
            entryDate = entryDate,
            entryPrice = entryPrice,
            signalDateLow = signalLow,
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

            // 优先级 1：止盈 — HIGH 触及入场价 × (1 + takeProfitPct)
            val tpPrice = round2(meta.entryPrice * (1.0 + config.takeProfitPct))
            if (bar.high.toDouble() + EPS >= tpPrice) {
                exits += exitOrder(
                    date = date,
                    tsCode = tsCode,
                    reason = "止盈: HIGH ${bar.high} 触及 +${(config.takeProfitPct * 100).toInt()}% ($tpPrice)",
                    hint = ExecutionHint.LIMIT,
                    limitPrice = tpPrice,
                )
                continue
            }

            // 优先级 2：时间止损 — T+N 收盘强制清仓
            // daysSinceEntry == timeStopDays - 1 表示已是第 N 个交易日
            if (daysSinceEntry >= config.timeStopDays - 1) {
                exits += exitOrder(
                    date = date,
                    tsCode = tsCode,
                    reason = "时间止损: 入场后第 ${daysSinceEntry + 1} 个交易日（T+${config.timeStopDays}），收盘 ${bar.close}",
                    hint = ExecutionHint.CLOSE,
                )
                continue
            }

            // 优先级 3：价格止损 — T+2 / T+3 收盘价 < T 日最低点
            if (config.priceStopEnabled && daysSinceEntry >= 1) {
                if (bar.close.toDouble() + EPS < meta.signalDateLow) {
                    exits += exitOrder(
                        date = date,
                        tsCode = tsCode,
                        reason = "价格止损: 收盘 ${bar.close} < T日最低 ${meta.signalDateLow}（入场后第 ${daysSinceEntry + 1} 日）",
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

        fun round2(v: Double): Double =
            BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}

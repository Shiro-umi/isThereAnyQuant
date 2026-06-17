package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.strategy.breakdown.BreakdownArr
import org.shiroumi.strategy.breakdown.detectBreakdownZigzag

/**
 * 破位加分判定服务（生产选股链路接缝）。
 *
 * 业务定义（已锁定口径，2026-06-16）：破位 = 加分。破位票（模型给高分但近期回归通道破位走弱）
 * 实证为「高分票被超跌、非被追高」，前置入场可减巨亏。本服务对一批高分候选票，判定每只是否在
 * 信号日 T 前 LOOKBACK 个交易日窗内出现破位事件，命中则在 [HoldingStateMachine.EntryCandidate]
 * 上置 breakdownFlag=true，由状态机入场排序将其排到所有非破位票之前。
 *
 * 锚与口径（与研究侧 temp/probe_zigzag_selection.py recently_broke 同源，且生产现算不复用 npz）：
 * - 信号日锚 = previousTradeDate = T（选股日；次日开盘买入）。
 * - 取窗：findRecentOhlcvWindowsForProduction(codes, endDateInclusive=T, limitPerStock=WINDOW_BARS)。
 * - 连续有效收盘序列 = closeQfq>0 过滤 + trade_date 升序 + 去重（与 Python build_candles 同口径）。
 * - searchsorted 定位 T 得 pos（序列内 T 的下标，非交易日历下标）；T 不在序列内 → 不判破位。
 * - 破位窗 = 交易日下标窗 [pos-LOOKBACK+1, pos] = [pos-2, pos]（含选股日）。
 * - 逐根 t 扫描整段序列，detectBreakdownZigzag 命中即记录破位事件下标；任一事件下标落 [pos-2, pos] → 破位。
 *
 * 检测器全程纯几何（只用 t 及之前窗口 OHLC，零未来数据），破位事件生产现算，不读任何缓存文件。
 *
 * 开关 quant.strategy.holding.breakdownRerank 默认 false：关闭时本服务不取窗、不判定，
 * 所有候选 breakdownFlag 恒为 false，入场排序退化为纯模型分降序（行为 = 现状，可一键回滚）。
 *
 * 两个 EntryCandidate 消费者（PostMarketPreparationJob 盘后推进 / StrategyPositionTrackingRuntime
 * 跟踪页重放）必须同源注入本服务，否则生产持仓与跟踪页重放分叉。
 */
object BreakdownRerankService {

    /** 破位回看窗（交易日，含信号日）：信号日 T 前 LOOKBACK 个交易日内破位 → 加分。 */
    const val LOOKBACK = 3

    /**
     * 取窗根数：80 根足以让破位窗 [pos-2, pos] 内每根 t 都有 W_LONG=20 + 缓冲的完整回看，
     * 检测器对 t<21 自行返回 null，窗内 t 落在序列尾部时 pos-2 远大于 21，破位事件无漏检。
     */
    private const val WINDOW_BARS = 80

    private const val SWITCH_PROPERTY = "quant.strategy.holding.breakdownRerank"

    /** 开关：仅当 quant.strategy.holding.breakdownRerank=true 时启用破位加分。默认 false。 */
    fun enabled(): Boolean =
        System.getProperty(SWITCH_PROPERTY, "false").toBoolean()

    /**
     * 对一批候选票判定破位加分标记。
     *
     * @param tsCodes 待判定的候选票代码（高分候选集合）
     * @param signalDate 信号日 T（= previousTradeDate；选股日，次日开盘买入）
     * @return code -> 是否破位（仅命中 true 的票出现在 map 中即可，调用方按 getOrDefault(code,false) 读取）。
     *   开关关闭或入参为空时返回空 map（全 false）。
     */
    fun evaluate(tsCodes: List<String>, signalDate: LocalDate): Map<String, Boolean> {
        if (!enabled() || tsCodes.isEmpty()) return emptyMap()
        val windows = StockDailyCandleRepository
            .findRecentOhlcvWindowsForProduction(tsCodes.distinct(), signalDate, WINDOW_BARS)
        val result = HashMap<String, Boolean>(windows.size)
        for ((code, rows) in windows) {
            result[code] = brokeRecently(rows, signalDate)
        }
        return result
    }

    /**
     * 单只票破位判定：构造连续有效收盘序列，searchsorted 定位信号日，判破位窗内是否有破位事件。
     *
     * @param rows findRecentOhlcvWindowsForProduction 返回的某票窗口行（DESC/ASC 顺序不依赖，内部重排）
     * @param signalDate 信号日 T
     */
    private fun brokeRecently(rows: List<ProductionOhlcvWindowRow>, signalDate: LocalDate): Boolean {
        // ---- 连续有效收盘序列：closeQfq>0 过滤 + trade_date 升序 + 去重（同 Python build_candles）----
        val valid = rows
            .filter { it.closeQfq > 0.0 }
            .sortedBy { it.tradeDate }
        if (valid.isEmpty()) return false
        val dates = ArrayList<LocalDate>(valid.size)
        val open = ArrayList<Double>(valid.size)
        val high = ArrayList<Double>(valid.size)
        val low = ArrayList<Double>(valid.size)
        val close = ArrayList<Double>(valid.size)
        var prevDate: LocalDate? = null
        for (r in valid) {
            // 去重：升序下相邻同日只保留首现（重叠面板同源 QFQ 值相同，去重保留任一份即可）
            if (r.tradeDate == prevDate) continue
            prevDate = r.tradeDate
            dates.add(r.tradeDate)
            open.add(r.openQfq)
            high.add(r.highQfq)
            low.add(r.lowQfq)
            close.add(r.closeQfq)
        }

        // ---- searchsorted 定位信号日 T 得 pos；T 不在序列内（停牌/数据缺）→ 不判破位 ----
        val pos = searchSorted(dates, signalDate)
        if (pos >= dates.size || dates[pos] != signalDate) return false

        // ---- 破位窗 [pos-LOOKBACK+1, pos] = [pos-2, pos]（交易日下标窗，含选股日）----
        val lo = maxOf(0, pos - LOOKBACK + 1)

        // ---- 逐根 t 扫描整段序列，破位事件下标落入 [lo, pos] 即破位（检测器对 t<21 自行 null）----
        val arr = BreakdownArr(
            open = open.toDoubleArray(),
            high = high.toDoubleArray(),
            low = low.toDoubleArray(),
            close = close.toDoubleArray(),
        )
        for (t in lo..pos) {
            if (detectBreakdownZigzag(arr, t) != null) return true
        }
        return false
    }

    /**
     * numpy.searchsorted(a, v) 左插入语义：返回 a 中保持升序插入 v 的最小下标 i（a[i-1] < v <= a[i]）。
     * dates 已升序去重。返回值 in [0, dates.size]。
     */
    private fun searchSorted(dates: List<LocalDate>, target: LocalDate): Int {
        var lo = 0
        var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

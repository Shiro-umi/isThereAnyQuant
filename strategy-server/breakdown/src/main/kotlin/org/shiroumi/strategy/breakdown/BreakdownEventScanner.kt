package org.shiroumi.strategy.breakdown

import kotlinx.datetime.LocalDate

/**
 * 单只票破位事件扫描器。
 *
 * 输入一只票的【连续有效收盘序列】（调用方已保证：close_qfq>0 过滤 + trade_date 升序 + 无重复日期，
 * 与 Python breakdown_base.build_candles 同口径）。逐根 t 调 detectBreakdownZigzag，
 * 收集破位事件交易日集合及每事件 lvl。
 *
 * 因果：检测器只用 t 及之前窗口，扫描整段序列不引入未来数据。
 */

/** 一只票的连续有效 K 线序列（dates 与 OHLC 下标一一对齐，升序去重）。 */
class BreakdownCandleSeries(
    val dates: List<LocalDate>,
    val arr: BreakdownArr,
) {
    init {
        require(dates.size == arr.n) { "dates 与 OHLC 长度不一致: ${dates.size} vs ${arr.n}" }
    }
}

/** 扫描结果：破位事件交易日集合 + 每事件交易日对应的破位位 lvl。 */
data class BreakdownScanResult(
    val eventDates: Set<LocalDate>,
    val lvlByDate: Map<LocalDate, Double>,
)

/**
 * 扫描一只票的连续序列，返回破位事件交易日集合 + 每事件 lvl。
 *
 * 逐根 t 从 0 调到 n-1（检测器内部对 t<21 等边界自行返回 null），命中即记录该交易日与 lvl。
 */
fun scanBreakdownEvents(series: BreakdownCandleSeries): BreakdownScanResult {
    val dates = series.dates
    val arr = series.arr
    val eventDates = LinkedHashSet<LocalDate>()
    val lvlByDate = LinkedHashMap<LocalDate, Double>()
    for (t in 0 until arr.n) {
        val event = detectBreakdownZigzag(arr, t) ?: continue
        val date = dates[t]
        eventDates.add(date)
        lvlByDate[date] = event.lvl
    }
    return BreakdownScanResult(eventDates = eventDates, lvlByDate = lvlByDate)
}

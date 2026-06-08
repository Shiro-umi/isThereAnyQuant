package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 检查某只股票在指定交易日是否涨停。
 *
 * 涨停判定：当日收盘价 >= 前收盘价 * (1 + 涨跌幅限制)
 *
 * @param tsCode 股票代码
 * @param tradeDate 交易日（即选出日/信号产生日）
 * @return true 表示该日收盘已涨停
 */
fun isLimitUpOnTradeDate(tsCode: String, tradeDate: LocalDate): Boolean {
    return tsCode in LimitUpOnTradeDateCache.limitUpSymbols(tradeDate)
}

/**
 * 批量预计算全部 [dates] 的涨停股票集合——全内存计算，零 DB 访问。
 *
 * 用于回测预加载路径：蜡烛数据和交易日历已在内存中，直接高效计算。
 */
fun preloadLimitUpSymbols(
    dates: List<LocalDate>,
    candlesByDate: Map<LocalDate, List<Candle>>,
    openDates: List<LocalDate>,
): Map<LocalDate, Set<String>> {
    val openDateIndex = openDates.withIndex().associate { it.value to it.index }
    val result = mutableMapOf<LocalDate, Set<String>>()

    fun previousTradingDate(date: LocalDate): LocalDate? {
        val index = openDateIndex[date] ?: return null
        return openDates.getOrNull(index - 1)
    }

    for (date in dates) {
        val prevDate = previousTradingDate(date) ?: continue
        val preClose = candlesByDate[prevDate].orEmpty()
            .associate { it.tsCode to it.close.toDouble() }
        if (preClose.isEmpty()) continue

        val limitUp = candlesByDate[date].orEmpty()
            .asSequence()
            .filter { candle ->
                val previousClose = preClose[candle.tsCode] ?: return@filter false
                val upper = round2(previousClose * (1.0 + limitFor(candle.tsCode)))
                candle.close.toDouble() + 1e-6 >= upper
            }
            .mapTo(linkedSetOf()) { it.tsCode }
        result[date] = limitUp
    }
    return result
}

private object LimitUpOnTradeDateCache {
    private val byDate: MutableMap<LocalDate, Set<String>> = mutableMapOf()

    fun limitUpSymbols(tradeDate: LocalDate): Set<String> =
        synchronized(byDate) {
            byDate.getOrPut(tradeDate) { loadLimitUpSymbols(tradeDate) }
        }

    private fun loadLimitUpSymbols(tradeDate: LocalDate): Set<String> {
        val previousDate = TradingCalendarRepository.findPreviousTradingDate(tradeDate) ?: return emptySet()
        val preClose = StockDailyCandleRepository.findByTradeDate(previousDate)
            .associate { it.tsCode to it.close.toDouble() }
        if (preClose.isEmpty()) return emptySet()

        return StockDailyCandleRepository.findByTradeDate(tradeDate)
            .asSequence()
            .filter { candle ->
                val previousClose = preClose[candle.tsCode] ?: return@filter false
                val upper = round2(previousClose * (1.0 + limitFor(candle.tsCode)))
                candle.close.toDouble() + 1e-6 >= upper
            }
            .mapTo(linkedSetOf()) { it.tsCode }
    }
}

private fun limitFor(tsCode: String): Double {
    val code = tsCode.substringBefore(".")
    val market = tsCode.substringAfter(".", missingDelimiterValue = "")
    return when {
        market.equals("BJ", ignoreCase = true) -> 0.20
        code.startsWith("688") -> 0.20
        code.startsWith("300") -> 0.20
        code.startsWith("301") -> 0.20
        else -> 0.10
    }
}

private fun round2(v: Double): Double =
    BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

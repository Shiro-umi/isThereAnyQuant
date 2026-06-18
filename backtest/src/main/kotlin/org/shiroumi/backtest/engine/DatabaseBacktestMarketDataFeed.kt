package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.shiroumi.backtest.config.RulesConfig
import org.shiroumi.database.common.repository.BacktestStockStatuses
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository

/**
 * stock_db 日线事实到回测执行行情的只读适配器。
 *
 * 回测执行口径固定 QFQ（前复权），与上游 agent 买点价、asof 历史取数路由（InternalCliAsofRoute）
 * 同一价格坐标系：agent 在 QFQ K 线上推演买点限价，撮合也必须用 QFQ OHLC 判 `low<=limit`，
 * 否则除权/价差标的的 RAW low 系统性高于 QFQ 限价，导致限价永不触发的系统性漏单。
 * QFQ 字段缺失（=0）时回退 RAW，与 asof 路由 `if (lowQfq>0) lowQfq else low` 完全同口径。
 * 策略信号口径仍由策略侧确认表决定，不在此处重新计算。
 */
class DatabaseBacktestMarketDataFeed(
    private val rules: RulesConfig = RulesConfig(),
) : BacktestMarketDataFeed {
    private val candlesByDate: MutableMap<LocalDate, List<model.Candle>> = mutableMapOf()
    private val statusesByDate: MutableMap<LocalDate, BacktestStockStatuses> = mutableMapOf()
    private val stockProfiles: List<StockBasicRepository.BacktestBasicProfile> by lazy {
        StockBasicRepository.findBacktestProfiles()
    }
    private val openDates: List<LocalDate> by lazy {
        TradingCalendarRepository.findOpenDates(LocalDate(1990, 1, 1), LocalDate(2035, 12, 31))
    }
    private val openDateIndex: Map<LocalDate, Int> by lazy {
        openDates.withIndex().associate { it.value to it.index }
    }

    override fun marketDataFor(date: LocalDate): DailyMarketData {
        val quotes = candlesFor(date)
            .associateBy { it.tsCode }
        val statuses = statusesFor(date)
        val previousDate = previousTradingDate(date)
        val preCloseCandles = previousDate
            ?.let { candlesFor(it) }
            .orEmpty()
        val preClose = preCloseCandles.associate { it.tsCode to it.close.toDouble() }

        val signalLimitUp = mutableSetOf<String>()
        if (rules.abandonIfSignalLimitUp && previousDate != null) {
            val prePreviousDate = previousTradingDate(previousDate)
            val prePreClose = prePreviousDate
                ?.let { candlesFor(it) }
                .orEmpty()
                .associate { it.tsCode to it.close.toDouble() }

            for (candle in preCloseCandles) {
                val tsCode = candle.tsCode
                val tClose = candle.close.toDouble()
                val tPreClose = prePreClose[tsCode] ?: continue

                val limitPct = limitFor(tsCode)
                val upper = round2(tPreClose * (1.0 + limitPct))
                if (tClose + EPS >= upper) {
                    signalLimitUp.add(tsCode)
                }
            }
        }

        return DailyMarketData(
            quotes = quotes,
            preClose = preClose,
            suspended = statuses.suspended,
            ipoFrozen = statuses.ipoFrozen,
            delisted = statuses.delisted,
            signalLimitUp = signalLimitUp,
        )
    }

    // 加载后统一收敛到 QFQ 坐标系（共享 [toQfqBasis]），与 agent 买点、asof 路由同标系。
    private fun candlesFor(date: LocalDate): List<model.Candle> =
        candlesByDate.getOrPut(date) {
            StockDailyCandleRepository.findByTradeDate(date).toQfqBasis()
        }

    private fun statusesFor(date: LocalDate): BacktestStockStatuses =
        statusesByDate.getOrPut(date) {
            val suspended = linkedSetOf<String>()
            val ipoFrozen = linkedSetOf<String>()
            val delisted = linkedSetOf<String>()
            for (profile in stockProfiles) {
                when (profile.listStatus.uppercase()) {
                    "D" -> delisted += profile.tsCode
                    "P" -> suspended += profile.tsCode
                }
                if (rules.excludeSt && profile.name.contains("ST", ignoreCase = true)) {
                    suspended += profile.tsCode
                }
                val listDate = profile.listDate
                if (listDate != null && isIpoFrozen(listDate, date)) {
                    ipoFrozen += profile.tsCode
                }
            }
            BacktestStockStatuses(
                suspended = suspended,
                ipoFrozen = ipoFrozen,
                delisted = delisted,
            )
        }

    private fun isIpoFrozen(listDate: LocalDate, tradeDate: LocalDate): Boolean {
        if (rules.excludeIpoDays <= 0 || tradeDate < listDate) return false
        if (listDate.daysUntil(tradeDate) > 20) return false
        val listIndex = firstOpenIndexOnOrAfter(listDate) ?: return false
        val tradeIndex = openDateIndex[tradeDate] ?: return false
        val openDays = tradeIndex - listIndex + 1
        return openDays in 1..rules.excludeIpoDays
    }

    private fun firstOpenIndexOnOrAfter(date: LocalDate): Int? {
        var low = 0
        var high = openDates.lastIndex
        var result: Int? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (openDates[mid] >= date) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun previousTradingDate(date: LocalDate): LocalDate? {
        val index = openDateIndex[date] ?: firstOpenIndexOnOrAfter(date) ?: return null
        return openDates.getOrNull(index - 1)
    }

    private fun limitFor(tsCode: String): Double {
        val code = tsCode.substringBefore(".")
        val market = tsCode.substringAfter(".", missingDelimiterValue = "")
        return when {
            market.equals("BJ", ignoreCase = true) -> rules.priceLimitGrowthBoard
            code.startsWith("688") -> rules.priceLimitGrowthBoard
            code.startsWith("300") -> rules.priceLimitGrowthBoard
            code.startsWith("301") -> rules.priceLimitGrowthBoard
            else -> rules.priceLimitMainBoard
        }
    }

    private fun round2(v: Double): Double =
        java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toDouble()

    private companion object {
        const val EPS = 1e-6
    }
}

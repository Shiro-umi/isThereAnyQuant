package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import model.Candle
import org.shiroumi.backtest.config.RulesConfig
import org.shiroumi.database.common.repository.BacktestStockStatuses
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 全内存行情数据 feed——构造时一次性批量预加载全部蜡烛，后续 [marketDataFor] 纯 map 查找。
 *
 * 消除逐日 DB 查询瓶颈：250 个交易日的回测从 ~500-750 次 DB 查询压缩为构造时 1 次批量查询。
 */
class PreloadedMarketDataFeed private constructor(
    private val marketDataByDate: Map<LocalDate, DailyMarketData>,
    /** 原始蜡烛数据，供涨停预计算等下游复用。 */
    val candlesByDate: Map<LocalDate, List<Candle>> = emptyMap(),
    /** 交易日历（开盘日期列表），供涨停预计算等下游复用。 */
    val openDates: List<LocalDate> = emptyList(),
) : BacktestMarketDataFeed {

    override fun marketDataFor(date: LocalDate): DailyMarketData =
        marketDataByDate[date] ?: DailyMarketData(emptyMap())

    companion object {
        /**
         * 从 stock_db 批量预加载 [from]..[to] 区间所需的全部行情数据。
         *
         * 加载策略：
         * - 蜡烛数据：1 次 `findByDateRange(candleFrom, to)` 查询整个区间（含前 2 个交易日缓冲）
         * - 股票基础信息：1 次 `findBacktestProfiles()`
         * - 交易日历：1 次 `findOpenDates()`
         * - 然后遍历每个交易日预计算 [DailyMarketData]
         */
        fun fromDatabase(
            rules: RulesConfig = RulesConfig(),
            from: LocalDate,
            to: LocalDate,
        ): PreloadedMarketDataFeed {
            // 交易日历
            val openDates = TradingCalendarRepository.findOpenDates(
                LocalDate(1990, 1, 1), LocalDate(2035, 12, 31)
            )
            val openDateIndex = openDates.withIndex().associate { it.value to it.index }

            // 蜡烛缓冲起点：向前推 2 个交易日，覆盖 preClose 和 signalLimitUp 计算
            val candleFrom = openDateIndex[from]
                ?.let { openDates.getOrElse((it - 2).coerceAtLeast(0)) { from } }
                ?: from

            val allCandles = StockDailyCandleRepository.findByDateRange(candleFrom, to)

            // 股票基础信息
            val stockProfiles = StockBasicRepository.findBacktestProfiles()

            // 不随交易日变化的停牌/退市集合
            val baseSuspended = linkedSetOf<String>()
            val delisted = linkedSetOf<String>()
            for (profile in stockProfiles) {
                when (profile.listStatus.uppercase()) {
                    "D" -> delisted += profile.tsCode
                    "P" -> baseSuspended += profile.tsCode
                }
                if (rules.excludeSt && profile.name.contains("ST", ignoreCase = true)) {
                    baseSuspended += profile.tsCode
                }
            }

            val tradingDays = openDates.filter { it in from..to }
            val data = mutableMapOf<LocalDate, DailyMarketData>()

            for (date in tradingDays) {
                val quotes = allCandles[date].orEmpty().associateBy { it.tsCode }

                val prevDate = previousTradingDate(date, openDates, openDateIndex)
                val preCloseCandles = prevDate?.let { allCandles[it] }.orEmpty()
                val preClose = preCloseCandles.associate { it.tsCode to it.close.toDouble() }

                // 逐日变化的 IPO 冻结集合
                val ipoFrozen = linkedSetOf<String>()
                if (rules.excludeIpoDays > 0) {
                    for (profile in stockProfiles) {
                        val listDate = profile.listDate
                        if (listDate != null && isIpoFrozen(
                                listDate, date, rules.excludeIpoDays, openDates, openDateIndex
                            )) {
                            ipoFrozen += profile.tsCode
                        }
                    }
                }

                // 信号日涨停集合
                val signalLimitUp = mutableSetOf<String>()
                if (rules.abandonIfSignalLimitUp && prevDate != null) {
                    val prePrevDate = previousTradingDate(prevDate, openDates, openDateIndex)
                    val prePreClose = prePrevDate
                        ?.let { allCandles[it] }
                        .orEmpty()
                        .associate { it.tsCode to it.close.toDouble() }

                    for (candle in preCloseCandles) {
                        val tClose = candle.close.toDouble()
                        val tPreClose = prePreClose[candle.tsCode] ?: continue
                        val limitPct = limitPctFor(candle.tsCode, rules)
                        val upper = round2(tPreClose * (1.0 + limitPct))
                        if (tClose + EPS >= upper) {
                            signalLimitUp += candle.tsCode
                        }
                    }
                }

                data[date] = DailyMarketData(
                    quotes = quotes,
                    preClose = preClose,
                    suspended = baseSuspended,
                    ipoFrozen = ipoFrozen,
                    delisted = delisted,
                    signalLimitUp = signalLimitUp,
                )
            }

            return PreloadedMarketDataFeed(data, allCandles, openDates)
        }

        // ---- 辅助函数 ----

        private fun previousTradingDate(
            date: LocalDate,
            openDates: List<LocalDate>,
            openDateIndex: Map<LocalDate, Int>,
        ): LocalDate? {
            val index = openDateIndex[date] ?: firstOpenIndexOnOrAfter(date, openDates) ?: return null
            return openDates.getOrNull(index - 1)
        }

        private fun firstOpenIndexOnOrAfter(date: LocalDate, openDates: List<LocalDate>): Int? {
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

        private fun isIpoFrozen(
            listDate: LocalDate,
            tradeDate: LocalDate,
            excludeIpoDays: Int,
            openDates: List<LocalDate>,
            openDateIndex: Map<LocalDate, Int>,
        ): Boolean {
            if (tradeDate < listDate) return false
            if (listDate.daysUntil(tradeDate) > 20) return false
            val listIndex = firstOpenIndexOnOrAfter(listDate, openDates) ?: return false
            val tradeIndex = openDateIndex[tradeDate] ?: return false
            val openDays = tradeIndex - listIndex + 1
            return openDays in 1..excludeIpoDays
        }

        private fun limitPctFor(tsCode: String, rules: RulesConfig): Double {
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
            BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

        private const val EPS = 1e-6
    }
}

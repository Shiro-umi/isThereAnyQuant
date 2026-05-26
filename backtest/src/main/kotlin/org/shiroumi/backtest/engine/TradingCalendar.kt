package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository

/**
 * 回测交易日历，只返回可交易日。
 */
fun interface TradingCalendar {
    fun tradingDays(startInclusive: LocalDate, endInclusive: LocalDate): List<LocalDate>
}

/** 基于 database 模块 calendar 表的交易日历。 */
object DbTradingCalendar : TradingCalendar {
    override fun tradingDays(startInclusive: LocalDate, endInclusive: LocalDate): List<LocalDate> =
        TradingCalendarRepository.findOpenDates(startInclusive, endInclusive)
}

/** 单测和离线回放使用的内存交易日历。 */
class InMemoryTradingCalendar(openDates: Collection<LocalDate>) : TradingCalendar {
    private val sortedOpenDates = openDates.toSortedSet()

    override fun tradingDays(startInclusive: LocalDate, endInclusive: LocalDate): List<LocalDate> =
        sortedOpenDates.filter { it in startInclusive..endInclusive }
}

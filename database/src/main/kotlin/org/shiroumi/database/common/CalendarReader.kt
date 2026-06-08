package org.shiroumi.database.common

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object CalendarReader {

    fun getRecentTradingDays(endDate: LocalDate, count: Int): List<LocalDate> {
        return stockDb.transaction(log = false) {
            CalendarTable.selectAll()
                .where { (CalendarTable.calDate lessEq endDate) and (CalendarTable.isOpen eq 1) }
                .orderBy(CalendarTable.calDate, SortOrder.DESC)
                .limit(count)
                .map { it[CalendarTable.calDate] }
        }
    }

    /** 区间 [start, end] 内全部开盘交易日，升序。供逐日批量同步（如 moneyflow）驱动循环。 */
    fun getTradingDaysBetween(start: LocalDate, end: LocalDate): List<LocalDate> {
        return stockDb.transaction(log = false) {
            CalendarTable.selectAll()
                .where { (CalendarTable.calDate greaterEq start) and (CalendarTable.calDate lessEq end) and (CalendarTable.isOpen eq 1) }
                .orderBy(CalendarTable.calDate, SortOrder.ASC)
                .map { it[CalendarTable.calDate] }
        }
    }
}

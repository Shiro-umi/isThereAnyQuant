package org.shiroumi.database.common

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.lessEq
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
}

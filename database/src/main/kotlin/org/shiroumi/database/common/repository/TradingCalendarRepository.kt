package org.shiroumi.database.common.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/**
 * 交易日历仓储。
 *
 * 这个仓储是新架构对 `calendar` 表的稳定访问边界，专门服务于盘后日线批处理同步。
 * 它只暴露两类能力：
 * 1. 找出哪些开市日还没有完成日线事实同步
 * 2. 在对应批次成功落库后，把这些交易日标记为已完成
 *
 * 这样 `ktor-server` 不需要再越过边界直接操作 `CalendarTable`。
 */
object TradingCalendarRepository {

    /**
     * 查询截至指定日期仍未完成日线同步的开市日。
     */
    fun findPendingStockDailyDates(todayInclusive: LocalDate): List<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq todayInclusive) and
                        (CalendarTable.stockDailyUpdated eq 0)
                }
                .sortedBy { it[CalendarTable.calDate] }
                .map { it[CalendarTable.calDate] }
        }
    }

    /**
     * 批量标记指定交易日的日线同步已完成。
     *
     * 必须只在“对应交易日事实已成功落库”之后调用。
     */
    fun markStockDailyUpdated(dates: List<LocalDate>) {
        if (dates.isEmpty()) return

        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = { CalendarTable.calDate inList dates.distinct() }
            ) {
                it[stockDailyUpdated] = 1
            }
        }
    }

    fun resetStockDailyUpdated(fromInclusive: LocalDate, toInclusive: LocalDate) {
        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = {
                    (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
            ) {
                it[stockDailyUpdated] = 0
            }
        }
    }

    fun findPendingLimitListDDates(todayInclusive: LocalDate): List<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq todayInclusive) and
                        (CalendarTable.stockDailyUpdated eq 1) and
                        (CalendarTable.limitListDUpdated eq 0)
                }
                .sortedBy { it[CalendarTable.calDate] }
                .map { it[CalendarTable.calDate] }
        }
    }

    fun markLimitListDUpdated(dates: List<LocalDate>) {
        if (dates.isEmpty()) return

        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = { CalendarTable.calDate inList dates.distinct() }
            ) {
                it[limitListDUpdated] = 1
            }
        }
    }

    fun resetLimitListDUpdated(fromInclusive: LocalDate, toInclusive: LocalDate) {
        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = {
                    (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
            ) {
                it[limitListDUpdated] = 0
            }
        }
    }

    fun findPendingStockDailyFqDates(todayInclusive: LocalDate): List<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq todayInclusive) and
                        (CalendarTable.stockDailyUpdated eq 1) and
                        (CalendarTable.stockDailyFqUpdated eq 0)
                }
                .sortedBy { it[CalendarTable.calDate] }
                .map { it[CalendarTable.calDate] }
        }
    }

    fun findPendingStrategyDates(todayInclusive: LocalDate): List<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq todayInclusive) and
                        (CalendarTable.stockDailyFqUpdated eq 1) and
                        (CalendarTable.strategyUpdated eq 0)
                }
                .sortedBy { it[CalendarTable.calDate] }
                .map { it[CalendarTable.calDate] }
        }
    }

    fun markStockDailyFqUpdated(dates: List<LocalDate>) {
        if (dates.isEmpty()) return

        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = { CalendarTable.calDate inList dates.distinct() }
            ) {
                it[stockDailyFqUpdated] = 1
            }
        }
    }

    fun resetStockDailyFqUpdated(fromInclusive: LocalDate, toInclusive: LocalDate) {
        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = {
                    (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
            ) {
                it[stockDailyFqUpdated] = 0
            }
        }
    }

    fun markStrategyUpdated(dates: List<LocalDate>) {
        if (dates.isEmpty()) return

        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = { CalendarTable.calDate inList dates.distinct() }
            ) {
                it[strategyUpdated] = 1
            }
        }
    }

    fun resetStrategyUpdated(fromInclusive: LocalDate, toInclusive: LocalDate) {
        stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.update(
                where = {
                    (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
            ) {
                it[strategyUpdated] = 0
            }
        }
    }

    fun findOpenDates(fromInclusive: LocalDate, toInclusive: LocalDate): List<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
                .orderBy(CalendarTable.calDate, SortOrder.ASC)
                .map { it[CalendarTable.calDate] }
        }
    }

    fun findLatestTradingDateOnOrBefore(dateInclusive: LocalDate): LocalDate? {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq dateInclusive)
                }
                .orderBy(CalendarTable.calDate, SortOrder.DESC)
                .limit(1)
                .map { it[CalendarTable.calDate] }
                .firstOrNull()
        }
    }

    fun findPreviousTradingDate(beforeDateExclusive: LocalDate): LocalDate? {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate lessEq beforeDateExclusive)
                }
                .orderBy(CalendarTable.calDate, SortOrder.DESC)
                .map { it[CalendarTable.calDate] }
                .firstOrNull { it < beforeDateExclusive }
        }
    }

    fun isStockDailyUpdated(tradeDate: LocalDate): Boolean {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where { CalendarTable.calDate eq tradeDate }
                .limit(1)
                .map { it[CalendarTable.stockDailyUpdated] == 1 }
                .firstOrNull() ?: false
        }
    }

    fun findStockDailyUpdatedOpenDates(fromInclusive: LocalDate, toInclusive: LocalDate): Set<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.stockDailyUpdated eq 1) and
                        (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
                .mapTo(linkedSetOf()) { it[CalendarTable.calDate] }
        }
    }

    fun isLimitListDUpdated(tradeDate: LocalDate): Boolean {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where { CalendarTable.calDate eq tradeDate }
                .limit(1)
                .map { it[CalendarTable.limitListDUpdated] == 1 }
                .firstOrNull() ?: false
        }
    }

    fun findStockDailyFqUpdatedOpenDates(fromInclusive: LocalDate, toInclusive: LocalDate): Set<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.stockDailyFqUpdated eq 1) and
                        (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
                .mapTo(linkedSetOf()) { it[CalendarTable.calDate] }
        }
    }

    fun isStockDailyFqUpdated(tradeDate: LocalDate): Boolean {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where { CalendarTable.calDate eq tradeDate }
                .limit(1)
                .map { it[CalendarTable.stockDailyFqUpdated] == 1 }
                .firstOrNull() ?: false
        }
    }

    fun findStrategyUpdatedOpenDates(fromInclusive: LocalDate, toInclusive: LocalDate): Set<LocalDate> {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.strategyUpdated eq 1) and
                        (CalendarTable.calDate greaterEq fromInclusive) and
                        (CalendarTable.calDate lessEq toInclusive)
                }
                .mapTo(linkedSetOf()) { it[CalendarTable.calDate] }
        }
    }

    fun isStrategyUpdated(tradeDate: LocalDate): Boolean {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where { CalendarTable.calDate eq tradeDate }
                .limit(1)
                .map { it[CalendarTable.strategyUpdated] == 1 }
                .firstOrNull() ?: false
        }
    }

    /**
     * 查询指定日期之后的下一个开市日。
     *
     * 这个能力专门服务于“盘后生成次日盘中 seed”：
     * - `T` 日盘后情绪计算完成后
     * - 需要精确知道 `T+1` 的下一个实际交易日
     * - seed 只能绑定到真实开市日，不能简单地日历加一天
     */
    fun findNextTradingDate(afterDateExclusive: LocalDate): LocalDate? {
        return stockDb.transaction(CalendarTable, log = false) {
            CalendarTable.selectAll()
                .where {
                    (CalendarTable.isOpen eq 1) and
                        (CalendarTable.calDate greater afterDateExclusive)
                }
                .sortedBy { it[CalendarTable.calDate] }
                .map { it[CalendarTable.calDate] }
                .firstOrNull()
        }
    }
}

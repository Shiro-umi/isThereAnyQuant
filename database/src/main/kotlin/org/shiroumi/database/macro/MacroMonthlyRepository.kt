package org.shiroumi.database.macro

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.macro.table.MacroMonthlyTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/**
 * 宏观月频事实记录（一行 = 一个 YYYYMM）。
 * 字段允许为空：三个源的可得月份不完全一致，对齐后缺失列留 null。
 */
data class MacroMonthlyRecord(
    val yearMonth: Int,
    val sfIncMonth: Double? = null,
    val sfIncCumval: Double? = null,
    val sfStkEndval: Double? = null,
    val pmiMfg: Double? = null,
    val pmiNonMfg: Double? = null,
    val pmiComposite: Double? = null,
    val shiborOn: Double? = null,
    val shibor3m: Double? = null,
    val shibor1y: Double? = null,
)

/**
 * macro_monthly 读写。upsert 按 yearMonth 主键合并，便于三个源分别落库后逐列补齐。
 */
object MacroMonthlyRepository {

    fun upsert(records: List<MacroMonthlyRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(MacroMonthlyTable, log = false) {
            MacroMonthlyTable.batchUpsert(records) { r ->
                this[MacroMonthlyTable.yearMonth] = r.yearMonth
                r.sfIncMonth?.let { this[MacroMonthlyTable.sfIncMonth] = it }
                r.sfIncCumval?.let { this[MacroMonthlyTable.sfIncCumval] = it }
                r.sfStkEndval?.let { this[MacroMonthlyTable.sfStkEndval] = it }
                r.pmiMfg?.let { this[MacroMonthlyTable.pmiMfg] = it }
                r.pmiNonMfg?.let { this[MacroMonthlyTable.pmiNonMfg] = it }
                r.pmiComposite?.let { this[MacroMonthlyTable.pmiComposite] = it }
                r.shiborOn?.let { this[MacroMonthlyTable.shiborOn] = it }
                r.shibor3m?.let { this[MacroMonthlyTable.shibor3m] = it }
                r.shibor1y?.let { this[MacroMonthlyTable.shibor1y] = it }
            }
        }
    }

    fun findBetween(startYm: Int, endYm: Int): List<MacroMonthlyRecord> =
        stockDb.transaction(MacroMonthlyTable, log = false) {
            MacroMonthlyTable.selectAll()
                .where { MacroMonthlyTable.yearMonth.between(startYm, endYm) }
                .orderBy(MacroMonthlyTable.yearMonth, SortOrder.ASC)
                .map(::toRecord)
        }

    fun count(): Long =
        stockDb.transaction(MacroMonthlyTable, log = false) {
            MacroMonthlyTable.selectAll().count()
        }

    private fun toRecord(row: ResultRow) = MacroMonthlyRecord(
        yearMonth = row[MacroMonthlyTable.yearMonth],
        sfIncMonth = row[MacroMonthlyTable.sfIncMonth],
        sfIncCumval = row[MacroMonthlyTable.sfIncCumval],
        sfStkEndval = row[MacroMonthlyTable.sfStkEndval],
        pmiMfg = row[MacroMonthlyTable.pmiMfg],
        pmiNonMfg = row[MacroMonthlyTable.pmiNonMfg],
        pmiComposite = row[MacroMonthlyTable.pmiComposite],
        shiborOn = row[MacroMonthlyTable.shiborOn],
        shibor3m = row[MacroMonthlyTable.shibor3m],
        shibor1y = row[MacroMonthlyTable.shibor1y],
    )
}

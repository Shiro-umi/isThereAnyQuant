package org.shiroumi.database.common.updater

import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.getCalendar
import org.shiroumi.network.apis.tushare
import utils.localDate
import utils.logger
import kotlin.time.ExperimentalTime

private val logger by logger("updateCalendar")

/**
 * 名称	            类型	     默认显示	    描述
 * exchange	        str	     Y	            交易所 SSE上交所 SZSE深交所
 * cal_date	        str	     Y	            日历日期
 * is_open	        str	     Y	            是否交易 0休市 1交易
 * pretrade_date    str	     Y	            上一个交易日
 */
@OptIn(ExperimentalTime::class)
suspend fun updateCalendar() = runCatching {
    val form = tushare.getCalendar().check() ?: return@runCatching
    val calendar = form.items.map { (_, calDate, isOpen, _) ->
        calDate!!.localDate to isOpen
    }
    stockDb.transaction(CalendarTable, log = false) {
        val existed = CalendarTable.selectAll().map { it[CalendarTable.calDate] }.toSet()
        val toInsert = calendar.filter { it.first !in existed }
        if (toInsert.isNotEmpty()) {
            CalendarTable.batchReplace(toInsert) { (calDate, isOpen) ->
                this[CalendarTable.calDate] = calDate
                this[CalendarTable.isOpen] = isOpen?.toInt() ?: 0
            }
        }
    }
    logger.accept("update calendar done.")
}.onFailure {
    it.printStackTrace()
}

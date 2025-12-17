package org.shiroumi.database.common.updater

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.getCalendar
import org.shiroumi.network.apis.tushare
import utils.localDate
import kotlin.time.ExperimentalTime

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
    commonDb.transaction(CalendarTable) {
        CalendarTable.batchReplace(calendar) {(calDate, isOpen) ->
            this[CalendarTable.calDate] = calDate
            this[CalendarTable.isOpen] = 0
        }
    }
}.onFailure {
    it.printStackTrace()
}

package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.date

object CalendarTable : UUIDTable("calendar") {
    val calDate = date("cal_date").uniqueIndex()
    val isOpen = integer("is_open")

    val stockDailyUpdated = integer("stock_daily_updated").default(0)
    val stockFqUpdated = integer("stock_fq_updated").default(0)
    val swIndexDailyUpdated = integer("sw_index_daily_updated").default(0)

}
package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.date

object CalendarTable : UUIDTable("calendar") {
    val calDate = date("cal_date").uniqueIndex()
    val isOpen = integer("is_open")

    val stockDailyUpdated = integer("stock_daily_updated").default(0)
    val limitListDUpdated = integer("limit_list_d_updated").default(0)
    val stockDailyFqUpdated = integer("stock_daily_fq_updated").default(0)
    val strategyUpdated = integer("strategy_updated").default(0)
}

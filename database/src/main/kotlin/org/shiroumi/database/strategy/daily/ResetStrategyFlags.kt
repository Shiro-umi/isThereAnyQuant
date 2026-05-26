package org.shiroumi.database.strategy.daily

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/**
 * 重置策略计算的 flag，使下次 runDataUpdate 时重新计算情绪指标。
 * @return 受影响的记录数
 */
fun resetStrategyFlags(): Int {
    return stockDb.transaction(CalendarTable) {
        CalendarTable.update({ CalendarTable.strategyUpdated eq 1 }) {
            it[strategyUpdated] = 0
        }
    }
}

package org.shiroumi.database.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.ktorm.dsl.*
import org.shiroumi.database.akApi
import org.shiroumi.database.table.TradingDateTable
import org.shiroumi.database.table.bulkInsert

// update trading date
suspend fun updateTradingDate() = coroutineScope {
    launch(Dispatchers.IO) {
        val last = TradingDateTable.query { query ->
            query.select()
                .orderBy(TradingDateTable.date.desc())
                .limit(1)
                .map { column -> column[TradingDateTable.date] }
                .toList()
        }.lastOrNull()
        var bultToInsert = akApi.getTradingDate().onEach { td -> td.date = td.date.substring(0, 10).replace("-", "") }
        last?.let { from ->
            bultToInsert = bultToInsert.filter { d -> d.date > from }.sortedByDescending { d -> d.date }
            if (bultToInsert.isEmpty()) {
                println("trading date is up to date")
                return@launch
            }
        }
        TradingDateTable.bulkInsert insert@{
            bultToInsert.forEach { i ->
                this@insert.item {
                    set(TradingDateTable.date, i.date)
                }
            }
        }
    }
}
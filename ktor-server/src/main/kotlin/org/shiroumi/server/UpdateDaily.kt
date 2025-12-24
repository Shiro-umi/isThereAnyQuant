package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.common.updater.updateIndex
import org.shiroumi.database.stock.updater.updateStockDaily
import org.shiroumi.database.stock.updater.updateStockDailyFq

fun main() {
    runBlocking {
//        updateCalendar()
//        updateStockBasic()
//        updateStockDaily()
//        updateStockDailyFq()
//        updateSwIndex()
        updateIndex()


//        updateStockBasic()
//        updateDailyCandles()
//        updateIndexDaily()
//        updateIndustryClassify()
//        updateSwIndexDaily()
//        updateIndexMember()
//        calculateAdjCandle()
    }
}
package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.common.updater.updateStockBasic
import org.shiroumi.database.common.updater.updateSwIndex
import org.shiroumi.database.stock.updater.updateStockDailyCandle

fun main() {
    runBlocking {
        updateCalendar()
        updateStockBasic()
        updateSwIndex()
        updateStockDailyCandle()


//        updateStockBasic()
//        updateDailyCandles()
//        updateIndexDaily()
//        updateIndustryClassify()
//        updateSwIndexDaily()
//        updateIndexMember()
//        calculateAdjCandle()
    }
}
package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.database.datasource.calculateAdjCandle
import org.shiroumi.database.datasource.updateDailyCandles
import org.shiroumi.database.datasource.updateIndexDaily
import org.shiroumi.database.datasource.updateStockBasic

fun main() {
    runBlocking {
//        updateStockBasic()
//        updateDailyCandles()
        updateIndexDaily()
//        calculateAdjCandle()
    }
}
package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.database.datasource.calculateAdjCandle
import org.shiroumi.database.datasource.updateDailyCandles
import org.shiroumi.database.datasource.updateStockBasic
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun main() {
    runBlocking {
        updateStockBasic()
        updateDailyCandles()
        calculateAdjCandle()
    }
}
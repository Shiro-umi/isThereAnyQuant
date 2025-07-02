package org.shiroumi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.shiroumi.database.getStockBasic
import org.shiroumi.database.tushare

// vm entry
fun main() {
    // startup
    println("xxxxxx")
    runBlocking {
        val output = tushare.getStockBasic()
        println(output)
//        startApplication()
    }
}

// application entry
suspend fun startApplication() = coroutineScope {
//    EngineMain.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

package org.shiroumi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.shiroumi.database.datasource.updateDailyCandles
import org.shiroumi.database.getAdjFactor
import org.shiroumi.database.getDailyCandles
import org.shiroumi.database.tushare

// vm entry
fun main() {
    // startup

    runBlocking {
//        updateStockBasic()
//        println(tushare.getAdjFactor("000001.SZ"))
//        println(tushare.getDailyCandles("000001.SZ"))
//        startApplication()
        println("xxxxxx")
//        tushare.getAdjFactor(date = "19901219").also { data ->
//            data.onSucceed { res ->
//                res!!.items.forEach { item ->
//                    logger.error("adj: $item")
//                }
//            }
//        }
//        tushare.getDailyCandles(date = "19901219").also { data ->
//            data.onSucceed { res ->
//                res!!.items.forEach { item ->
//                    logger.error("candle: $item")
//                }
//            }
//        }
        updateDailyCandles()
//        select()
    }
}

// application entry
suspend fun startApplication() = coroutineScope {
//    EngineMain.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

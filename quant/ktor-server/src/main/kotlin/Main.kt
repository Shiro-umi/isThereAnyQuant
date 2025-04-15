package org.shiroumi

import io.ktor.server.netty.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

// vm entry
fun main() {
    // startup
    runBlocking {
        startApplication()
    }
}

// application entry
suspend fun startApplication() = coroutineScope {
    EngineMain.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

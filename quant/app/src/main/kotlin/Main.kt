package org.shiroumi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import datasource.updateSymbol
import org.shiroumi.datasource.updateTradingDate
import kotlin.system.exitProcess

// vm entry
fun main() {
    // startup
    runBlocking { startApplication() }
    exitProcess(1)
}

// application entry
suspend fun startApplication() = coroutineScope {
//    updateSymbol() // update all symbols
    updateTradingDate()
    // todo update all

}

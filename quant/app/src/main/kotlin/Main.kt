package org.shiroumi

import datasource.updateSymbol
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
    updateSymbol()
    updateTradingDate()
    // todo update all

}

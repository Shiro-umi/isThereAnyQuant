package org.shiroumi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import datasource.updateSymbol
import kotlin.system.exitProcess

// vm entry
fun main() {
    // startup
    runBlocking { startApplication() }
    exitProcess(1)
}

// application entry
suspend fun startApplication() = coroutineScope {
    // update all symbols
    updateSymbol()
    // update all
}

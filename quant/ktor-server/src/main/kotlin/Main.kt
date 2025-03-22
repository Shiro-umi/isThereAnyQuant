package org.shiroumi

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.shiroumi.configs.BuildConfigs

val cpuCores = Runtime.getRuntime().availableProcessors()

// vm entry
fun main() {
    // startup
    runBlocking {
        startApplication()
    }
}

// application entry
suspend fun startApplication() = coroutineScope {
    println(BuildConfigs.SCRIPT_BASE_DIR)

    io.ktor.server.netty.EngineMain.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

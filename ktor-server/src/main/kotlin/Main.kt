import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.shiroumi.database.datasource.calculateAdjCandle
import org.shiroumi.database.datasource.updateDailyCandles
import org.shiroumi.database.datasource.updateStockBasic

// vm entry
fun main() {
    // startup
    runBlocking {
//        updateStockBasic()
//        updateDailyCandles()
        calculateAdjCandle()
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

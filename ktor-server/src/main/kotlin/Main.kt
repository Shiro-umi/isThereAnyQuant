import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.apis.chat
import org.shiroumi.network.apis.llmApi
import org.shiroumi.network.apis.Message

// vm entry
fun main() {
    // startup
    runBlocking {
//        updateStockBasic()
//        updateDailyCandles()
//        calculateAdjCandle()
        val msgs = listOf(
            Message(role = "system", content = "You are a helpful assistant"),
            Message(role = "user", content = "Hi"),
        )
        val res = llmApi.chat(
            model = "deepseek-chat",
            messages = msgs
        )
        println(res.choices.first().message)
    }
}

// application entry
suspend fun startApplication() = coroutineScope {
//    EngineMain.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

package org.shiroumi.server

import ScheduledTasks
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import ktor.module.SiliconFlow
import logger
import org.shiroumi.database.datasource.calculateAdjCandle
import org.shiroumi.database.datasource.updateDailyCandles
import org.shiroumi.database.datasource.updateStockBasic
import org.shiroumi.database.functioncalling.getJoinedCandles
import org.shiroumi.network.apis.getThsHotStocks
import org.shiroumi.network.apis.tushare
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi


private val logger by logger("Processor")

fun main() {
    runBlocking {
        updateStockBasic()
        updateDailyCandles()
        calculateAdjCandle()
    }
}

// vm entry
@OptIn(ExperimentalAtomicApi::class)
fun main(args: Array<String>) {
    // startup
    runBlocking {



//        processSpecific("301379.SZ")
//        return@runBlocking

//        val arg = args.firstOrNull() ?: run {
//            logger.error("params need. current arg[0] is null")
//            return@runBlocking
//        }
        val arg = "601606.SH"
//        val arg = "000810.SZ"

        if (arg == "thsHot") {
            processThsHot(35)
            return@runBlocking
        }
        if (arg.isNotBlank() && ((".SZ" in arg) || ".SH" in arg)) {
            processSpecific(arg)
            return@runBlocking
        }
        logger.error("params need. current arg[0] is $arg")
    }
}


@OptIn(ExperimentalAtomicApi::class)
private suspend fun processThsHot(count: Int) {
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    val thsHot = mutableMapOf<String, Pair<String, String>>()
    tushare.getThsHotStocks(tradeDate = today).check()!!.items.forEach {
        thsHot["${it[2]}"] = "${it[3]}" to "${it[4]}"
    }
    val sorted = thsHot.toList().subList(0, count)
    val scheduledTasks = ScheduledTasks<Any>()
    val count = AtomicInt(0)
    val tasks = sorted.map { (tsCode, pair) ->
        suspend {
            processSpecific(tsCode)
            logger.accept("code: $tsCode done. ${count.addAndFetch(1)}/count")
        }
    }
    scheduledTasks.emit("reasoning", *tasks.toTypedArray())
    scheduledTasks.schedule().collect()
}

private suspend fun processSpecific(tsCode: String) {
    val stock = getJoinedCandles(tsCode, limit = 30)
    if (stock.res.isEmpty()) throw Exception("no candle list.")
    alBrooks.chat(getUserPrompt(stock)) { content ->
        val title = content.substringBefore("\n")
        File("${System.getProperty("user.dir")}/$title.md").writeText(content)
        logger.notify("result of $tsCode saved to file \"$title.md\"")
    }
}


private val alBrooks: SiliconFlow
    get() = SiliconFlow(
        prompt = systemPrompt + systemPrompt1,
        model = SiliconFlow.SiliconFlowModel.KimiK2
    )

// application entry
suspend fun startApplication() = coroutineScope {
//    EngineMain.org.shiroumi.ktor.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

private const val systemPrompt = """
"""

const val systemPrompt1 = """
"""

private fun getUserPrompt(data: Any) = """
       
        
        $data
        """.trimIndent()
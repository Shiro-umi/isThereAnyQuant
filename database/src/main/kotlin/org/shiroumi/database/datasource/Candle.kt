package org.shiroumi.database.datasource

import AsyncTask
import Logger
import f
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import logger
import org.ktorm.dsl.and
import org.ktorm.dsl.asc
import org.ktorm.dsl.batchUpdate
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.map
import org.ktorm.dsl.orderBy
import org.ktorm.dsl.select
import org.ktorm.entity.lastOrNull
import org.ktorm.entity.toList
import org.ktorm.support.mysql.bulkInsert
import org.shiroumi.database.*
import org.shiroumi.database.table.candleSeq
import org.shiroumi.database.table.candleTable
import org.shiroumi.database.table.stockBasicSeq
import org.shiroumi.generated.assignments.setCandle
import org.shiroumi.model.database.Candle
import runConcurrent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi


@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
suspend fun updateDailyCandles() = coroutineScope {
    val logger by logger("Daily")
    val dbLogger by logger("DailyDatabase")
    launch(CoroutineExceptionHandler { coroutineContext, throwable ->
        logger.error("${throwable.message}")
        throwable.printStackTrace()
    }) {
        val dates = tushare.getTradingDate()
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val allStocks = stockBasicSeq.toList()

        val lastUpdatedDate = allStocks.first().tsCode.candleSeq.lastOrNull()?.tradeDate ?: "19910403"
        val tradingDate = dates!!.items.filter { (exchange, date, isOpen, preTradingDate) ->
            date != null && date > lastUpdatedDate && date <= today && isOpen != "0"
        }.sortedBy { (exchange, date, isOpen, preTradingDate) -> date }

        tradingDate.map { (exchange, date, isOpen, preTradingDate) ->
            AsyncTask(
                tag = "$date",
                { tushare.getAdjFactor(date = date).check()?.items },
                { tushare.getDailyCandles(date = date).check()?.items }
            )
        }.asFlow().runConcurrent(tag = "request tushare", frequency = 300).mapNotNull { (factor, candles) ->
            if (factor.isNullOrEmpty() || candles.isNullOrEmpty()) return@mapNotNull null
            var factor = factor.filter { f -> candles.any { c -> f[0] == c[0] } }
            var candles = candles.filter { c -> factor.any { f -> c[0] == f[0] } }
            factor = factor.sortedBy { item -> item[0] }
            candles = candles.sortedBy { item -> item[0] }
            candles.mapIndexed { j, day ->
                Candle {
                    tsCode = "${day[0]}"
                    tradeDate = "${day[1]}"
                    close = day.getOrNull(2).f
                    open = day.getOrNull(3).f
                    high = day.getOrNull(4).f
                    low = day.getOrNull(5).f
                    vol = day.getOrNull(9).f
                    amount = day.getOrNull(10).f
                    adjFactor = factor[j].getOrNull(2).f
                }
            }
        }.chunked(500).collect { days ->
            logger.notify("chunk received (size = ${days.size})")
            logger.notify("calculating...")
            val cache = HashMap<String, MutableList<Candle>>()
            days.forEach { day ->
                day.forEach { candle ->
                    cache[candle.tsCode] = (cache[candle.tsCode] ?: mutableListOf()).also { list -> list.add(candle) }
                }
            }
            logger.notify("insert chunk to database..")
            cache.insertAll(dbLogger)
            logger.notify("done!")
        }

    }
}

private fun HashMap<String, MutableList<Candle>>.insertAll(logger: Logger) {
    logger.info("inserting candles to database..")
    forEach { (tsCode, candles) ->
        val table = tsCode.candleTable
        try {
            stockDb.bulkInsert(table) {
                candles.forEach { candle ->
                    item {
                        setCandle(table, candle)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("candles insert failed: ${e.message}, code: $tsCode")
        }
    }
    logger.info("done!")
}

private fun Candle.hfq(firstCandle: Candle?) {
    val fc = firstCandle ?: run {
        openHfq = open
        closeHfq = close
        highHfq = high
        lowHfq = low
        return
    }
    openHfq = fc.open / fc.adjFactor * adjFactor
    closeHfq = fc.close / fc.adjFactor * adjFactor
    highHfq = fc.high / fc.adjFactor * adjFactor
    lowHfq = fc.low / fc.adjFactor * adjFactor
}
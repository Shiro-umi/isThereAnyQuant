package org.shiroumi.database.datasource

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import logger
import org.ktorm.entity.clear
import org.ktorm.entity.toList
import org.ktorm.support.mysql.bulkInsert
import org.shiroumi.database.*
import org.shiroumi.database.table.candleSeq
import org.shiroumi.database.table.candleTable
import org.shiroumi.database.table.stockBasicSeq
import org.shiroumi.generated.assignments.setCandle
import org.shiroumi.model.database.Candle
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


private val logger by logger("updateDailyCandles")

@OptIn(ExperimentalAtomicApi::class)
suspend fun updateDailyCandles() = coroutineScope {
    launch(CoroutineExceptionHandler { coroutineContext, throwable ->
        logger.error("${throwable.message}")
        throwable.printStackTrace()
    }) {
        val stockDb = stockDb
        val allStocks = stockBasicSeq.toList()
        allStocks.scheduledAsync(
            logger = logger,
            asyncTasks = listOf(
                AsyncTask(tag = "getAdjFactor", task =  { stock -> tushare.getAdjFactor(stock.tsCode) }),
                AsyncTask(tag = "getDailyCandles", task =  { stock -> tushare.getDailyCandles(stock.tsCode) })
            )
        ) { stock, (adjFactorResp, dailyResp) ->
            stock.tsCode.candleSeq.clear()
            val (_, adjFactor) = suspendCoroutine { continuation ->
                adjFactorResp.onSucceed { continuation.resume(it!!) }
                    .onFail { msg ->
                        logger.error("query adj factor failed: $msg")
                    }
            }
            val (_, daily) = suspendCoroutine { continuation ->
                dailyResp.onSucceed { continuation.resume(it!!) }
                    .onFail { msg -> logger.error("query daily candle failed: $msg") }
            }
            val candles = (0 until daily.size).map { i ->
                val tTsCode = "${daily[i][0]}"
                val tTradDate = "${daily[i][1]}"
                val tOpen = daily.getOrNull(i)?.getOrNull(2)?.toFloat() ?: 0f
                val tHigh = daily.getOrNull(i)?.getOrNull(3)?.toFloat() ?: 0f
                val tLow = daily.getOrNull(i)?.getOrNull(4)?.toFloat() ?: 0f
                val tClose = daily.getOrNull(i)?.getOrNull(5)?.toFloat() ?: 0f
                val tFactor = adjFactor.getOrNull(i)?.getOrNull(2)?.toFloat() ?: 0f
                val tLatestFactor = adjFactor.firstOrNull()?.getOrNull(2)?.toFloat() ?: 0f
                val tVol = daily.getOrNull(i)?.getOrNull(9)?.toFloat() ?: 0f
                val tAmount = daily.getOrNull(i)?.getOrNull(10)?.toFloat() ?: 0f
                return@map Candle {
                    tsCode = tTsCode
                    tradeDate = tTradDate
                    close = tClose
                    closeQfq = tClose.qfq(tFactor, tLatestFactor)
                    closeHfq = tClose.hfq(tFactor)
                    open = tOpen
                    openQfq = tOpen.qfq(tFactor, tLatestFactor)
                    openHfq = tOpen.hfq(tFactor)
                    high = tHigh
                    highQfq = tHigh.qfq(tFactor, tLatestFactor)
                    highHfq = tHigh.hfq(tFactor)
                    low = tLow
                    lowQfq = tLow.qfq(tFactor, tLatestFactor)
                    lowHfq = tLow.hfq(tFactor)
                    vol = tVol
                    amount = tAmount
                }
            }
            stockDb.bulkInsert(stock.tsCode.candleTable) insert@{
                candles.forEach { candle ->
                    this@insert.item { c ->
                        setCandle(c, candle)
                    }
                }
            }
            logger.accept("candles updated: code: ${stock.tsCode}")
        }
    }
}

private fun Float.qfq(factor: Float, latestFactor: Float) = this * factor / latestFactor

private fun Float.hfq(factor: Float) = this * factor

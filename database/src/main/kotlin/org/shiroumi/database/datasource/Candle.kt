package org.shiroumi.database.datasource

import cpuCores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logger
import org.ktorm.entity.clear
import org.ktorm.entity.toList
import org.ktorm.support.mysql.bulkInsert
import org.shiroumi.database.getAdjFactor
import org.shiroumi.database.getDailyCandles
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.candleSeq
import org.shiroumi.database.table.candleTable
import org.shiroumi.database.table.stockBasicSeq
import org.shiroumi.database.tushare
import org.shiroumi.generated.assignments.setCandle
import org.shiroumi.model.database.Candle
import printProgressBar
import supervisorScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


private val logger by logger("updateDailyCandles")

@OptIn(ExperimentalAtomicApi::class)
suspend fun updateDailyCandles() = coroutineScope {
    val stockDb = stockDb
    val allStocks = stockBasicSeq.toList()
    val toUpdateCount = AtomicInt(allStocks.size)
    val updateCount = AtomicInt(0)
    with(Semaphore(cpuCores * 2)) {
        allStocks.onEach { stock ->
            withPermit {
                val start = System.currentTimeMillis()
                updateCount.fetchAndAdd(1)
                printProgressBar(updateCount.load(), toUpdateCount.load())
                // clear the stock candle table, calculate adjust then update all
                stock.tsCode.candleSeq.clear()
                val adjFactorDeferred = async { tushare.getAdjFactor(stock.tsCode) }
                val dailyDeferred = async { tushare.getDailyCandles(stock.tsCode) }
                val (adjFactorResp, dailyResp) = suspendCoroutine { continuation ->
                    supervisorScope.launch(Dispatchers.IO) {
                        continuation.resume(adjFactorDeferred.await() to dailyDeferred.await())
                    }
                }
                val (_, adjFactor) = suspendCoroutine { continuation ->
                    adjFactorResp.onSucceed { continuation.resume(it) }
                        .onFail { msg -> logger.error("query adj factor failed: $msg") }
                }
                val (_, daily) = suspendCoroutine { continuation ->
                    dailyResp.onSucceed { continuation.resume(it) }
                        .onFail { msg -> logger.error("query daily candle failed: $msg") }
                }
                val candles = (0 until daily.size).map { i ->
                    val tTsCode = "${daily[i][0]}"
                    val tTradDate = "${daily[i][1]}"
                    val tOpen = daily[i][2]!!.toFloat()
                    val tHigh = daily[i][3]!!.toFloat()
                    val tLow = daily[i][4]!!.toFloat()
                    val tClose = daily[i][5]!!.toFloat()
                    val tFactor = adjFactor[i][2]?.toFloat() ?: 0f
                    val tLatestFactor = adjFactor.first()[2]?.toFloat() ?: 0f
                    val tVol = daily[i][9]?.toFloat() ?: 0f
                    val tAmount = daily[i][10]?.toFloat() ?: 0f
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
                candles.chunked(500) { chunked ->
                    stockDb.bulkInsert(stock.tsCode.candleTable) insert@{
                        chunked.forEach { candle ->
                            this@insert.item { c ->
                                setCandle(c, candle)
                            }
                        }
                    }
                }
                logger.notify("candles updated: code: ${stock.tsCode}, time: ${System.currentTimeMillis() - start}ms")
            }
        }
    }
}

private fun Float.qfq(factor: Float, latestFactor: Float) = this * factor / latestFactor

private fun Float.hfq(factor: Float) = this * factor

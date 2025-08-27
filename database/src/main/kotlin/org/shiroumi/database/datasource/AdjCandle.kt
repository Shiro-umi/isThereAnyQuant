package org.shiroumi.database.datasource

import ScheduledTasks
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import logger
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.AdjCandleTable
import org.shiroumi.database.table.AdjFactorTable
import org.shiroumi.database.table.DailyCandleTable
import org.shiroumi.database.table.StockTable
import org.shiroumi.database.transaction
import kotlin.reflect.KProperty0

suspend fun calculateAdjCandle() = coroutineScope {
    val logger by logger("CalculateAdj")
    launch(CoroutineExceptionHandler { _, t ->
        logger.error("${t.message}")
        t.printStackTrace()
    }) {
        val allStocks = stockDb.transaction {
            StockTable.select(StockTable.tsCode, StockTable.name).map { row ->
                row[StockTable.tsCode] to row[StockTable.name]
            }
        }.toList()
        logger.notify("all stocks: ${allStocks.size}")

        val scheduledTasks = ScheduledTasks<Any>()
        allStocks.forEachIndexed { i, (tsCode, name) ->
            scheduledTasks.emit(
                tag = tsCode,
                {
                    logger.notify("each: $tsCode")
                    val candle = stockDb.transaction(DailyCandleTable, AdjFactorTable, log = true) {
                        (DailyCandleTable leftJoin AdjFactorTable)
                            .select(
                                DailyCandleTable.tsCode,
                                DailyCandleTable.tradeDate,
                                DailyCandleTable.close,
                                DailyCandleTable.open,
                                DailyCandleTable.high,
                                DailyCandleTable.low,
                                DailyCandleTable.vol,
                                AdjFactorTable.adjFactor
                            )
                            .where { (DailyCandleTable.tsCode eq tsCode) and (DailyCandleTable.tradeDate eq AdjFactorTable.tradeDate) }
                            .sortedBy { DailyCandleTable.tradeDate }
                            .map { row ->
                                RawCandle(
                                    row[DailyCandleTable.tsCode],
                                    row[DailyCandleTable.tradeDate],
                                    row[DailyCandleTable.close],
                                    row[DailyCandleTable.open],
                                    row[DailyCandleTable.high],
                                    row[DailyCandleTable.low],
                                    row[DailyCandleTable.vol],
                                    row[AdjFactorTable.adjFactor]
                                )
                            }
                    }.toList()
                    logger.notify("joined for $tsCode, ${allStocks.size}")
                    stockDb.transaction(AdjCandleTable, log = false) {
                        AdjCandleTable.batchUpsert(candle) { raw ->
                            set(AdjCandleTable.tsCode, raw.tsCode)
                            set(AdjCandleTable.tradeDate, raw.tradeDate)
                            set(AdjCandleTable.closeHfq, raw.hfq(raw::close))
                            set(AdjCandleTable.openHfq, raw.hfq(raw::open))
                            set(AdjCandleTable.highHfq, raw.hfq(raw::high))
                            set(AdjCandleTable.lowHfq, raw.hfq(raw::low))
                            set(AdjCandleTable.volFq, raw.hfq(raw::vol))
                            set(AdjCandleTable.closeQfq, raw.qfq(raw::close))
                            set(AdjCandleTable.openQfq, raw.qfq(raw::open))
                            set(AdjCandleTable.highQfq, raw.qfq(raw::high))
                            set(AdjCandleTable.lowQfq, raw.qfq(raw::low))
                        }
                    }
                    logger.notify("calculate adj_candle, code:$tsCode done.  $i/${allStocks.size}")
                }
            )
        }
        scheduledTasks.schedule().collect()
    }
}

data class RawCandle(
    val tsCode: String,
    val tradeDate: String,
    val close: Float,
    val open: Float,
    val high: Float,
    val low: Float,
    val vol: Float,
    val adjFactor: Float,
) {
    fun hfq(p: KProperty0<Float>) = p.get() * adjFactor

    fun qfq(p: KProperty0<Float>) = p.get() / adjFactor

}
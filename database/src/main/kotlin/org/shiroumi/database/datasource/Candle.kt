package org.shiroumi.database.datasource

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.AdjFactorTable
import org.shiroumi.database.table.Candle
import org.shiroumi.database.table.DailyCandleTable
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.*
import utils.ScheduledTasks
import utils.f
import utils.logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi


@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
suspend fun updateDailyCandles() = coroutineScope {
    val logger by logger("Daily")
    launch(CoroutineExceptionHandler { _, t ->
        logger.error("${t.message}")
        t.printStackTrace()
    }) {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val lastUpdated = stockDb.transaction(DailyCandleTable) {
            Candle.all().orderBy(DailyCandleTable.tradeDate to SortOrder.DESC).limit(1).firstOrNull()
        }?.tradeDate ?: "19900101"
        val dates = tushare.getTradingDate()!!.items.filter { (exchange, date, isOpen, preTradingDate) ->
            date != null && date > lastUpdated && date <= today && isOpen != "0"
        }.sortedBy { (exchange, date, isOpen, preTradingDate) -> date }

        val scheduledTasks = ScheduledTasks<TushareForm?>()
        dates.forEach { (exchange, date, isOpen, preTradingDate) ->
            scheduledTasks.emit(
                tag = "$date",
                { tushare.getAdjFactor(date = date).check() },
                { tushare.getDailyCandles(date = date).check() },
            )
        }
        scheduledTasks.schedule()
            .mapNotNull mnn@{ (tag, form)->
                val (adjForm, candleForm) = form
                adjForm ?: return@mnn null
                candleForm ?: return@mnn null
                val factors = adjForm.toColumns(sortKey = "ts_code")
                val candles = candleForm.toColumns(sortKey = "ts_code")
                tag to (candles to factors)
            }
            .chunked(200)
            .collect { chunked ->
                stockDb.transaction(DailyCandleTable, AdjFactorTable, log = false) {
                    chunked.forEach { (tag, form) ->
                        val (candles, factors) = form
                        DailyCandleTable.batchUpsert(candles) { c ->
                            set(DailyCandleTable.tsCode, c provides "ts_code")
                            set(DailyCandleTable.tradeDate, c provides "trade_date")
                            set(DailyCandleTable.close, (c provides "close").f)
                            set(DailyCandleTable.open, (c provides "open").f)
                            set(DailyCandleTable.high, (c provides "high").f)
                            set(DailyCandleTable.low, (c provides "low").f)
                            set(DailyCandleTable.vol, (c provides "vol").f)
                        }
                        AdjFactorTable.batchUpsert(factors) { f ->
                            set(AdjFactorTable.tsCode, f provides "ts_code")
                            set(AdjFactorTable.tradeDate, f provides "trade_date")
                            set(AdjFactorTable.adjFactor, (f provides "adj_factor").f)
                        }
                        logger.accept(tag)
                    }
                }
            }
    }
}
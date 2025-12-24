@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.database.stock.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import model.Candle
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.stock.table.StockCandleTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import utils.ScheduledTasks
import utils.logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger by logger("updateStockDailyFq")
suspend fun updateStockDailyFq() = runCatching {
    val tsCodes = commonDb.transaction {
        StockBasicTable.select(StockBasicTable.tsCode).map { it[StockBasicTable.tsCode] }.toList()
    }
    val tables = tsCodes.map { it to object : StockCandleTable("`${it}`") {} }
    val schedular = ScheduledTasks<Unit>()
    tables.forEach { (tsCode, table) ->
        schedular.emit(table.tableName) {
            stockDb.transaction(table, log = false) {
                val candles = table.selectAll().orderBy(table.date).map {
                    Candle(
                        id = Uuid.parse("${it[table.id]}"),
                        tsCode = tsCode,
                        date = it[table.date],
                        open = it[table.open],
                        high = it[table.high],
                        low = it[table.low],
                        close = it[table.close],
                        adj = it[table.adj],
                        volume = it[table.volume],
                        turnoverReal = it[table.turnoverReal],
                        pe = it[table.pe],
                        peTtm = it[table.peTtm],
                        pb = it[table.pb],
                        ps = it[table.ps],
                        psTtm = it[table.psTtm],
                        mvTotal = it[table.mvTotal],
                        mvCirc = it[table.mvCirc]
                    )
                }.toList()
                val last = candles.last()
                table.batchReplace(candles.map { c ->
                    c.copy(
                        openQfq = (c.adj / last.adj) * c.open,
                        highQfq = (c.adj / last.adj) * c.high,
                        lowQfq = (c.adj / last.adj) * c.low,
                        closeQfq = (c.adj / last.adj) * c.close,
                        volumeQfq = c.adj * last.volume
                    )
                }) { c ->
                    this[table.date] = c.date
                    this[table.open] = c.open
                    this[table.high] = c.high
                    this[table.low] = c.low
                    this[table.close] = c.close
                    this[table.adj] = c.adj
                    this[table.volume] = c.volume
                    this[table.openQfq] = c.openQfq
                    this[table.highQfq] = c.highQfq
                    this[table.lowQfq] = c.lowQfq
                    this[table.closeQfq] = c.closeQfq
                    this[table.volumeQfq] = c.volumeQfq
                    this[table.turnoverReal] = c.turnoverReal
                    this[table.pe] = c.pe
                    this[table.peTtm] = c.peTtm
                    this[table.pb] = c.pb
                    this[table.ps] = c.ps
                    this[table.psTtm] = c.psTtm
                    this[table.mvTotal] = c.mvTotal
                    this[table.mvCirc] = c.mvCirc
                }
            }
        }
    }
    schedular.schedule(Dispatchers.IO).collect { (tsCode, _) ->
        logger.accept("$tsCode done.")
    }
//    codeTables.map { it.second }
//    stockDb.transaction()
}
package org.shiroumi.database.stock.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.stock.table.StockCandleTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.*
import utils.ScheduledTasks
import utils.localDate
import utils.logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger by logger("updateStockDailyCandle")

@OptIn(ExperimentalAtomicApi::class)
suspend fun updateStockDailyCandle() = runCatching {
    val basicInfo = commonDb.transaction(StockBasicTable) {
        StockBasicTable.selectAll().map { it[StockBasicTable.tsCode] to it[StockBasicTable.name] }
    }
    val scheduledTasks = ScheduledTasks<Unit>(frequency = 200)
    basicInfo.forEach { (tsCode, name) ->
        scheduledTasks.emit(tag = "$name [$tsCode]") {
            updateStock(tsCode = tsCode, name = name)
        }
    }
    val count = AtomicInt(0)
    scheduledTasks.schedule().collect { (tag, _) ->
        logger.warning("tag [$tag] done. {${count.addAndFetch(1)}/${basicInfo.size}}")
    }

}.onFailure { t ->
    t.printStackTrace()
}

/**
 * getDailyCandles:
 * [ts_code, trade_date, open, high, low, close, pre_close, change, pct_chg, vol, amount]
 *
 * getAdjFactor:
 * [ts_code, trade_date, adj_factor]
 *
 * getDailyInfo:
 * [ts_code, trade_date, close, turnover_rate, turnover_rate_f, volume_ratio, pe, pe_ttm, pb, ps, ps_ttm, dv_ratio, dv_ttm, total_share, float_share, free_share, total_mv, circ_mv]
 */
private suspend fun updateStock(tsCode: String, name: String) {
    val table = object : StockCandleTable(tsCode = "`$tsCode`") {}
    withContext(Dispatchers.Default) {
        var (dailyCandle, adjFactor, info, limit) = listOf(
            async { tushare.getDailyCandles(tsCode = tsCode).check()!!.items.asReversed() },
            async { tushare.getAdjFactor(tsCode = tsCode).check()!!.items.asReversed() },
            async { tushare.getDailyInfo(tsCode = tsCode).check()!!.items.asReversed() },
            async { tushare.getDailyLimit(tsCode = tsCode).check()!!.items.asReversed() }
        ).awaitAll()
        val winCtx = minOf(dailyCandle.size, adjFactor.size, info.size)
        dailyCandle = dailyCandle.takeLast(winCtx)
        adjFactor = adjFactor.takeLast(winCtx)
        info = info.takeLast(winCtx)

        stockDb.transaction(table, log = false) {
            val latestAdj = "${adjFactor.first()[2]}".toFloat()

            table.batchReplace(
                data = dailyCandle.asSequence()
                    .zip(adjFactor.asSequence()) { a, b -> a to b }
                    .zip(info.asSequence()) { (a, b), c -> listOf(a, b, c) }
                    .zip(limit.asSequence()) { (a, b, c), d -> listOf(a, b, c, d) }
                    .toList()
            ) { (daily, adj, info, limit) ->
                this[table.date] = "${daily[1]}".localDate
                this[table.turnoverReal] = "${info[4] ?: -1}".toFloat()
                this[table.pe] = "${info[6] ?: -1}".toFloat()
                this[table.peTtm] = "${info[7] ?: -1}".toFloat()
                this[table.pb] = "${info[8] ?: -1}".toFloat()
                this[table.ps] = "${info[9] ?: -1}".toFloat()
                this[table.psTtm] = "${info[10] ?: -1}".toFloat()
                this[table.mvTotal] = "${info[16] ?: -1}".toFloat()
                this[table.mvCirc] = "${info[17] ?: -1}".toFloat()

                // fq
                val open = "${daily[2]}".toFloat()
                val high = "${daily[3]}".toFloat()
                val low = "${daily[4]}".toFloat()
                val close = "${daily[5]}".toFloat()
                val volume = "${daily[9]}".toFloat()
                val adj = "${adj[2]}".toFloat()
                this[table.open] = open
                this[table.high] = high
                this[table.low] = low
                this[table.close] = close
                this[table.volume] = volume
                this[table.openQfq] = (adj / latestAdj) * open
                this[table.highQfq] = (adj / latestAdj) * high
                this[table.lowQfq] = (adj / latestAdj) * low
                this[table.closeQfq] = (adj / latestAdj) * close
                this[table.volumeQfq] = adj * volume
            }
            println("$name[$tsCode] updated.")
        }
    }
}
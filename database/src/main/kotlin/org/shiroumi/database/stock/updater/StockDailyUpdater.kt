@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package org.shiroumi.database.stock.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import model.Candle
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.stock.table.StockCandleTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.getAdjFactor
import org.shiroumi.network.apis.getDailyCandles
import org.shiroumi.network.apis.getDailyInfo
import org.shiroumi.network.apis.getDailyLimit
import org.shiroumi.network.apis.tushare
import utils.ScheduledTasks
import utils.localDate
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3
import kotlin.collections.component4
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val compactDateFormat = LocalDate.Format {
    year()
    monthNumber(padding = Padding.ZERO)
    day(padding = Padding.ZERO)
}

@OptIn(ExperimentalTime::class)
private val today = kotlin.time.Clock.System.now()
    .toLocalDateTime(TimeZone.currentSystemDefault())
    .date

@OptIn(ExperimentalAtomicApi::class)
suspend fun updateStockDaily() = runCatching {
    val calendar = commonDb.transaction {
        CalendarTable.selectAll()
            .sortedBy { it[CalendarTable.calDate] }
            .filter { it[CalendarTable.isOpen] == 1 && it[CalendarTable.calDate] <= today && it[CalendarTable.stockDailyUpdated] == 0 }
            .map { compactDateFormat.format(it[CalendarTable.calDate]) }
            .toList()
    }
    val schedular = ScheduledTasks<List<Candle>>(concurrency = 300)
    calendar.forEach { date ->
        schedular.emit(tag = date) { updateOneDay(date = date) }
    }
    schedular.schedule().chunked(200).collect { chunk ->
        val byCode: Map<String, List<Candle>> = chunk
            .flatMap { (_, candleList) -> candleList }
            .groupBy { it.tsCode }
        val tables = byCode.keys.map { it to object : StockCandleTable(tsCode = "`$it`") {} }
        stockDb.transaction(*tables.map { it.second }.toTypedArray(), log = false) {
            tables.forEach { (tsCode, table) ->
                table.batchReplace(byCode[tsCode]!!) { candle ->
                    this[table.date] = candle.date
                    this[table.open] = candle.open
                    this[table.high] = candle.high
                    this[table.low] = candle.low
                    this[table.close] = candle.close
                    this[table.volume] = candle.volume
                    this[table.adj] = candle.adj
                    this[table.turnoverReal] = candle.turnoverReal
                    this[table.pe] = candle.pe
                    this[table.peTtm] = candle.peTtm
                    this[table.pb] = candle.pb
                    this[table.ps] = candle.ps
                    this[table.psTtm] = candle.psTtm
                    this[table.mvTotal] = candle.mvTotal
                    this[table.mvCirc] = candle.mvCirc
                }
            }
        }
        commonDb.transaction(CalendarTable) {
            val dates = chunk.map { it.first.localDate }.distinct()
            CalendarTable.update(
                where = { CalendarTable.calDate inList dates }
            ) {
                it[stockDailyUpdated] = 1
            }
        }
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
private suspend fun updateOneDay(date: String) = withContext(Dispatchers.Default) {
    val (rawCandleList, rawAdjList, rawDailyInfoList) = listOf(
        async { tushare.getDailyCandles(date = date).check()!!.items.asReversed() },
        async { tushare.getAdjFactor(date = date).check()!!.items.asReversed() },
        async { tushare.getDailyInfo(date = date).check()!!.items.asReversed() },
    ).awaitAll()

    val candleCodes = rawCandleList.map { it[0] }.toSet()
    val adjCodes = rawAdjList.map { it[0] }.toSet()
    val dailyInfoCodes = rawDailyInfoList.map { it[0] }.toSet()

    val commonCodes = candleCodes.intersect(adjCodes).intersect(dailyInfoCodes)

    val candleList = rawCandleList.filter { it[0] in commonCodes }.sortedBy { it[0] }
    val adjList = rawAdjList.filter { it[0] in commonCodes }.sortedBy { it[0] }
    val dailyInfoList = rawDailyInfoList.filter { it[0] in commonCodes }.sortedBy { it[0] }

    (0 until candleList.size).map { i ->
        val candle = candleList[i]
        val adjFactor = adjList[i]
        val info = dailyInfoList[i]
        Candle(
            tsCode = "${candleList[i][0]}",
            date = "${candleList[i][1]}".localDate,
            turnoverReal = "${info[4] ?: -1}".toFloat(),
            pe = "${info[6] ?: -1}".toFloat(),
            peTtm = "${info[7] ?: -1}".toFloat(),
            pb = "${info[8] ?: -1}".toFloat(),
            ps = "${info[9] ?: -1}".toFloat(),
            psTtm = "${info[10] ?: -1}".toFloat(),
            mvTotal = "${info[16] ?: -1}".toFloat(),
            mvCirc = "${info[17] ?: -1}".toFloat(),
            open = "${candle[2]}".toFloat(),
            high = "${candle[3]}".toFloat(),
            low = "${candle[4]}".toFloat(),
            close = "${candle[5]}".toFloat(),
            volume = "${candle[9]}".toFloat(),
            adj = "${adjFactor[2]}".toFloat()
        )
    }
}

fun main() {
    runBlocking {
//        updateStockDaily()
    }
}

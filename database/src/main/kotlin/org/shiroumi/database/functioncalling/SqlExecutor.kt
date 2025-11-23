package org.shiroumi.database.functioncalling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import model.symbol.Wave
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.*
import org.shiroumi.database.transaction
import java.lang.Float.max
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs

data class JoinedCandles(
    val tsCode: String,
    val name: String,
    val res: List<Candle>
)

@Serializable
data class Candle(
    val date: String,
    val open: Float,
    val close: Float,
    val low: Float,
    val high: Float,
    val vol: Float,
) {
    override fun toString(): String = listOf(
        "tradeDate=${date}",
        "close=$close",
        "low=$low",
        "high=$high",
        "open=$open",
        "vol=$vol"
    ).toString()
}

val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

fun getJoinedCandles(tsCode: String, limit: Int = 60, endDate: String = today): JoinedCandles {
    var name = ""
    val res = stockDb.transaction {
        val rows = DailyCandleTable.join(
            AdjCandleTable,
            JoinType.LEFT,
            additionalConstraint = { (DailyCandleTable.tsCode eq AdjCandleTable.tsCode) }
        ).join(
            StockTable,
            JoinType.LEFT,
            additionalConstraint = { (DailyCandleTable.tsCode eq StockTable.tsCode) }
        ).select(
            DailyCandleTable.tsCode,
            StockTable.name,
            DailyCandleTable.tradeDate,
            AdjCandleTable.closeQfq,
            AdjCandleTable.lowQfq,
            AdjCandleTable.highQfq,
            AdjCandleTable.openQfq,
            AdjCandleTable.volFq,
        )
            .where {
                (DailyCandleTable.tsCode eq tsCode) and (DailyCandleTable.tradeDate eq AdjCandleTable.tradeDate) and (DailyCandleTable.tsCode eq StockTable.tsCode) and (DailyCandleTable.tradeDate lessEq endDate)
            }
            .orderBy(DailyCandleTable.tradeDate, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                name.ifBlank { name = row[StockTable.name] }
                val date = row[DailyCandleTable.tradeDate]
                val close = row[AdjCandleTable.closeQfq]
                val low = row[AdjCandleTable.lowQfq]
                val high = row[AdjCandleTable.highQfq]
                val open = row[AdjCandleTable.openQfq]
                val vol = row[AdjCandleTable.volFq]
                Candle(date, open, close, low, high, vol)
            }.toList().asReversed().distinctBy { it.date }
        return@transaction rows.subList(rows.lastIndex - limit + 1, rows.lastIndex + 1)
    }
    println("getJoinedCandles: ${res.size}")
    return JoinedCandles(tsCode = tsCode, name = name, res = res)
}

fun getAllStocks() = stockDb.transaction(StockTable) {
    Stock.all().map { it.tsCode }.toList()
}

private fun List<Candle>.calculateTr(i: Int): Float {
    if (i == 0) return 0f
    val pClose = this[i - 1].close
    val c = this[i]
    val tr1 = abs(c.high - c.low)
    val tr2 = abs(c.high - pClose)
    val tr3 = abs(c.low - pClose)
    return max(tr1, max(tr2, tr3))
}


fun upsertStrategy(tsCode: String, name: String, targetDate: String, startTime: Long, res: String) {
    stockDb.transaction(StrategyTable, log = true) {
        StrategyTable.upsert { s ->
            s[StrategyTable.tsCode] = tsCode
            s[StrategyTable.name] = name
            s[StrategyTable.tradeDate] = targetDate
            s[StrategyTable.startTime] = startTime
            s[StrategyTable.strategy] = res
        }
    }
}

fun fetchDoneTasks(): List<List<String>> = stockDb.transaction {
    Strategy.all().sortedByDescending { it.startTime }.map { s ->
        listOf("${s.id}", s.tsCode, s.name, s.tradeDate, s.startTime.toString())
    }
}

fun fetchDoneTask(uuid: String): String = stockDb.transaction {
    Strategy.findById(UUID.fromString(uuid))?.strategy ?: ""
}

fun getStockName(tsCode: String): String = stockDb.transaction {
    val res = Stock.find { StockTable.tsCode eq tsCode }.toList().firstOrNull()
    res?.name ?: throw Exception("stock $tsCode not found")
}

fun insertSymbolized(waves: List<Wave>, candles: List<model.Candle>): String = stockDb.transaction(SymbolizedTable) {
    val json = Json { prettyPrint = false }
    val symbolized = Symbolized.new {
        source = json.encodeToString(candles)
        symbols = json.encodeToString(waves)
    }
    return@transaction symbolized.id.value.toString()
}



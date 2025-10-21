package org.shiroumi.database.functioncalling

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

data class Candle(
    val date: String,
    val open: Float,
    val close: Float,
    val low: Float,
    val high: Float,
    val vol: Float,
    val ibs: Float,
    var tr: Float = 0f,
    var atr: Float = 0f,
    var ema20: Float = 0f,
    var ema20Slope: Float = 0f
) {
    override fun toString(): String = listOf(
        "tradeDate=${date}",
        "close=$close",
        "low=$low",
        "high=$high",
        "open=$open",
        "vol=$vol",
        "ibs=$ibs",
        "atr=$atr",
        "normAtr=${atr / close}",
        "ema20=$ema20",
        "ema20Slope=$ema20Slope"
    ).toString()
}

private val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

fun getJoinedCandles(tsCode: String, limit: Int, endDate: String = today): JoinedCandles {
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
                val ibs = ((close - low) / (high - low)) * 100
                Candle(date, open, close, low, high, vol, ibs)
            }.toList().asReversed().distinctBy { it.date }
        rows.calculateEma20()
        rows.calculateAtr()
        return@transaction rows.subList(rows.lastIndex - 30, rows.lastIndex + 1)
    }
    return JoinedCandles(tsCode = tsCode, name = name, res = res)
}

private fun List<Candle>.calculateEma20() {
    val period = 20
    if (this.size < period) return
    val multiplier = 2.0 / (period + 1)
    var ema: Float = this[period - 1].close
    var prevEma: Float? = null
    val slopes = mutableListOf<Float>()

    for (i in period until this.size) {
        ema = ((this[i].close * multiplier) + (ema * (1 - multiplier))).toFloat()
        this[i].ema20 = ema

        // 计算EMA20的斜率
        if (prevEma != null) {
            val slope = ema - prevEma
            this[i].ema20Slope = slope
            slopes.add(slope)
        }

        // 更新前一个EMA20值
        prevEma = ema
    }

    // 归一化斜率
    if (slopes.isNotEmpty()) {
        val minSlope = slopes.minOrNull() ?: 0f
        val maxSlope = slopes.maxOrNull() ?: 0f
        val range = maxSlope - minSlope

        for (i in period until this.size) {
            if (range > 0) {
                this[i].ema20Slope = (this[i].ema20Slope - minSlope) / range
            } else {
                this[i].ema20Slope = 0f
            }
        }
    }
}

private fun List<Candle>.calculateAtr() {
    val n = 14
    var p = 0
    var sum = 0f
    while (p < n) {
        this[p].tr = calculateTr(p)
        sum += this[p].tr
        this[p].atr = 0f
        p++
    }
    this[p].atr = sum / n.toFloat()
    p++
    while (p < this.size) {
        this[p].tr = calculateTr(p)
        this[p].atr = (this[p - 1].atr * (n - 1) + this[p].tr) / n
        p++
    }
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

package org.shiroumi.database.functioncalling

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.AdjCandleTable
import org.shiroumi.database.table.DailyCandleTable
import org.shiroumi.database.table.Stock
import org.shiroumi.database.table.StockTable
import org.shiroumi.database.transaction

@Suppress("DuplicatedCode")
fun getCandles(
    tsCode: String,
    limit: Int,
    endDate: String = today
): List<Candle> = stockDb.transaction {
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

fun randomTsCode() = stockDb.transaction {
    Stock.all().toList().random().tsCode
}

@Suppress("DuplicatedCode")
fun getRandomCandleSeq(length: Int = 60): List<Candle> = stockDb.transaction {
    val randomStock = randomTsCode()
    val allRows = DailyCandleTable.join(
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
            (DailyCandleTable.tsCode eq randomStock) and (DailyCandleTable.tradeDate eq AdjCandleTable.tradeDate) and (DailyCandleTable.tsCode eq StockTable.tsCode)
        }
        .orderBy(DailyCandleTable.tradeDate, SortOrder.ASC)
        .map { row ->
            val date = row[DailyCandleTable.tradeDate]
            val close = row[AdjCandleTable.closeQfq]
            val low = row[AdjCandleTable.lowQfq]
            val high = row[AdjCandleTable.highQfq]
            val open = row[AdjCandleTable.openQfq]
            val vol = row[AdjCandleTable.volFq]
            Candle(date, open, close, low, high, vol)
        }.toList().distinctBy { it.date }

    if (allRows.size < length) {
        return@transaction emptyList()
    }

    val randomStartIndex = (0..(allRows.size - length)).random()
    return@transaction allRows.subList(randomStartIndex, randomStartIndex + length)
}
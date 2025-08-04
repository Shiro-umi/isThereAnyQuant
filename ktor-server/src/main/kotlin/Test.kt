package org.shiroumi

import kotlinx.coroutines.supervisorScope
import logger
import org.ktorm.dsl.*
import org.ktorm.entity.first
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.candleTable
import org.shiroumi.database.table.stockBasicSeq
import org.shiroumi.model.database.Candle
import java.text.DecimalFormat

val logger by logger("select")

suspend fun select() = supervisorScope {
    val iterator = stockBasicSeq.iterator()
    val x = stockBasicSeq.first()
//    while (iterator.hasNext()) {
//        val stock = iterator.next()
//    val stock = stockBasicSeq.first()
//    val table = stock.tsCode.candleTable
    val table = "002640.SZ".candleTable
    val candles = stockDb.from(table)
        .select(
            table.tsCode,
            table.tradeDate,
            table.openHfq,
            table.closeHfq,
            table.closeQfq,
            table.highHfq,
            table.lowHfq
        )
        .orderBy(table.tradeDate.desc())
        .limit(30)
        .map { res ->
            Candle {
                tsCode = res[table.tsCode]!!
                tradeDate = res[table.tradeDate]!!
                openHfq = res[table.openHfq]!!
                closeHfq = res[table.closeHfq]!!
                closeQfq = res[table.closeQfq]!!
                highHfq = res[table.highHfq]!!
                lowHfq = res[table.lowHfq]!!
            }
        }
        .toList()
    if (process(candles)) {
        logger.accept("002640.SZ")
    }
//    }
}

fun process(candles: List<Candle>): Boolean {
    val df = DecimalFormat("#.00")
    var sum = 0f
    logger.info("closeQfq: ${candles.map { df.format(it.closeQfq) }}")
    val ma10 = candles.mapIndexed { i, c ->
        if (i < 10) return@mapIndexed 0f.also { sum += c.closeQfq }
        sum += c.closeQfq
        return@mapIndexed sum / 10f.also { sum -= candles[i - 10].closeQfq }
    }
    logger.info("ma10: ${ma10.map { df.format(it) }}")
    val angle = ma10.mapIndexed { i, m ->
        if (i < 2) return@mapIndexed 0f
        return@mapIndexed m - ma10[i - 2]
    }
    val isRising = angle.mapIndexed { i, a ->
        if (i < 1) return@mapIndexed false
        return@mapIndexed (a - angle[i - 1]) > 0f
    }


    val today = candles.last()
    val todayIsRising = isRising.last()
    val last3DaysAvgVol = candles.subList(candles.size - 3, candles.size).sumOf { it.vol.toDouble() } / 3f


    return today.close > today.open &&
            (last3DaysAvgVol - today.vol) / last3DaysAvgVol < 0.25f &&
            todayIsRising
}
package datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.ktorm.dsl.*
import org.ktorm.entity.filter
import org.ktorm.entity.forEach
import org.ktorm.entity.toList
import org.ktorm.support.mysql.bulkInsert
import org.shiroumi.akApi
import org.shiroumi.cpuCores
import org.shiroumi.database.stockDb
import org.shiroumi.database.str
import org.shiroumi.database.table.candleTable
import org.shiroumi.database.table.symbolSeq
import org.shiroumi.database.table.tradingDateSeq
import org.shiroumi.database.today
import org.shiroumi.generated.assignments.setCandle
import org.shiroumi.model.database.Symbol

// update all stock candles
suspend fun updateStockCandles() = coroutineScope {
    launch(Dispatchers.IO) {
        // calculate last trading date
        val td = tradingDateSeq.filter { entitySeq ->
            entitySeq.date lessEq today.str
        }.filter { entitySeq ->
            entitySeq.date greater today.minusDays(5).str
        }.toList()
        val endTd = if (today.str == td.last().date) td.last().date else td[td.size - 2].date

        // for each symbols
        with(Semaphore(cpuCores*2)) {
            val action: suspend (Symbol) -> Unit = { symbol ->
                withPermit { getStockHist(symbol, endTd) }
            }
            symbolSeq.forEach { symbol ->
                launch(Dispatchers.IO) { action(symbol) }
            }
        }
    }
}

private suspend fun getStockHist(
    symbol: Symbol,
    end: String
) = coroutineScope {
    val table = symbol.code.candleTable
    val start = stockDb.from(table)
        .select()
        .orderBy(table.date.desc())
        .limit(1)
        .map { rowSet -> rowSet[table.date] }
        .firstOrNull()
    val candles = akApi.getStockHist(
        symbol = symbol.code,
        start = start,
        end = end
    )
    val update = candles.firstOrNull()
    update?.let { u ->
        stockDb.update(table) update@{ t ->
            setCandle(t, u.convert())
            where { t.date eq u.date }
        }
    }
    stockDb.bulkInsert(table) insert@{
        candles.forEach { candle ->
            this@insert.item { ta -> setCandle(ta, candle.convert()) }
        }
    }
}
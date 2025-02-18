package datasource

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.ktorm.dsl.*
import org.ktorm.entity.filter
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
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@Volatile
var updateCount: Int = 0

// update all stock candles
suspend fun updateStockCandles() = coroutineScope {
    launch(Dispatchers.IO) {
        updateCount = 0
        // calculate last trading date
        val td = tradingDateSeq.filter { entitySeq ->
            entitySeq.date lessEq today.str
        }.filter { entitySeq ->
            entitySeq.date greater today.minusDays(5).str
        }.toList()
        val endTd = if (today.str == td.last().date) td.last().date else td[td.size - 2].date

        val channel: Channel<Symbol> = Channel(cpuCores)
        launch {
            for (symbol in symbolSeq) {
                channel.send(symbol)
            }
        }
        with(Semaphore(cpuCores)) {
            // for each symbols
            for (symbol in channel) {
                launch(Dispatchers.IO) {
                    withPermit {
                        try {
                            getStockHist(symbol, endTd)
                            println("update succeed:  ${symbol.code}, ")
                        } catch (_: SocketTimeoutException) {
                            println("timeout: ${symbol.code}")
                            delay(2000)
                            channel.send(symbol)
                        }
                    }
                }
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
    candles.chunked(500) { chunked ->
        stockDb.bulkInsert(table) insert@{
            chunked.forEach { candle ->
                this@insert.item { ta ->
                    setCandle(ta, candle.convert { propertyName, origin ->
                        if (propertyName != "date") return@convert origin
                        else LocalDateTime.parse((origin as String), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    })
                }
            }
        }
    }
}
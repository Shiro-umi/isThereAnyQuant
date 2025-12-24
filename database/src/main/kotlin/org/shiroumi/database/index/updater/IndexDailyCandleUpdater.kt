package org.shiroumi.database.index.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.*
import kotlinx.datetime.format.Padding
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.*
import org.shiroumi.database.index.table.IndexDailyTable
import org.shiroumi.network.apis.getIndexDaily
import org.shiroumi.network.apis.tushare
import utils.logger
import kotlin.time.ExperimentalTime

private val logger by logger("IndexDailyCandleUpdater")

private val compactDateFormat = LocalDate.Format {
    year()
    monthNumber(padding = Padding.ZERO)
    day(padding = Padding.ZERO)
}

@OptIn(ExperimentalTime::class)
private val today = Clock.System.now()
    .toLocalDateTime(TimeZone.currentSystemDefault())
    .date

/**
 * @see <a href="https://tushare.pro/document/2?doc_id=95">指数日线行情</a>
 */
suspend fun fetchIndexDailyCandle(tsCode: String, listDate: String?) {
    val table = object : IndexDailyTable(tsCode = "`$tsCode`") {}
    indexDb.transaction(table, log = false) { }
    
    withContext(Dispatchers.Default) {
        val startStr = listDate ?: "19900101"
        val endStr = compactDateFormat.format(today)
        
        logger.info("fetch index daily candle of $tsCode from $startStr to $endStr")
        
        val startYear = startStr.substring(0, 4).toInt()
        val currentYear = today.year
        
        val yearsStep = 20
        var tempStartYear = startYear
        
        while (tempStartYear <= currentYear) {
            val batchStart = if (tempStartYear == startYear) startStr else "${tempStartYear}0101"
            val batchEndYear = tempStartYear + yearsStep - 1
            val batchEnd = if (batchEndYear >= currentYear) endStr else "${batchEndYear}1231"
            
            logger.info("fetching batch for $tsCode: $batchStart to $batchEnd")
            
            val res = tushare.getIndexDaily(tsCode = tsCode, startDate = batchStart, endDate = batchEnd).check()!!
            if (res.items.isNotEmpty()) {
                indexDb.transaction(log = false) {
                    table.batchReplace(res.items) { (tsCode, tradeDate, close, open, high, low, preClose, change, pctChg, vol, amount) ->
                        this[table.tsCode] = "$tsCode"
                        this[table.tradeDate] = "$tradeDate"
                        this[table.close] = "${close ?: 0}".toFloat()
                        this[table.open] = "${open ?: 0}".toFloat()
                        this[table.high] = "${high ?: 0}".toFloat()
                        this[table.low] = "${low ?: 0}".toFloat()
                        this[table.preClose] = "${preClose ?: 0}".toFloat()
                        this[table.change] = "${change ?: 0}".toFloat()
                        this[table.pctChg] = "${pctChg ?: 0}".toFloat()
                        this[table.vol] = "${vol ?: 0}".toFloat()
                        this[table.amount] = "${amount ?: 0}".toFloat()
                    }
                }
            }
            
            tempStartYear += yearsStep
        }
        
        logger.accept("fetch index daily candle of $tsCode done.")
    }
}

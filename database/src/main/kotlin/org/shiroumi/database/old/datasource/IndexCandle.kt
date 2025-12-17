//package org.shiroumi.database.old.datasource
//
//import kotlinx.coroutines.CoroutineExceptionHandler
//import kotlinx.coroutines.ExperimentalCoroutinesApi
//import kotlinx.coroutines.coroutineScope
//import kotlinx.coroutines.launch
//import org.jetbrains.exposed.v1.core.SortOrder
//import org.jetbrains.exposed.v1.jdbc.batchUpsert
//import org.shiroumi.database.old.stockDb
//import org.shiroumi.database.old.table.IndexCandle
//import org.shiroumi.database.old.table.IndexDailyCandleTable
//import org.shiroumi.database.old.transaction
//import org.shiroumi.network.apis.getIndexDaily
//import org.shiroumi.network.apis.tushare
//import utils.f
//import utils.logger
//import java.time.LocalDate
//import java.time.format.DateTimeFormatter
//import kotlin.concurrent.atomics.ExperimentalAtomicApi
//
//
//@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
//suspend fun updateIndexDaily() = coroutineScope {
//    val logger by logger("IndexDaily")
//    launch(CoroutineExceptionHandler { _, t ->
//        logger.error("${t.message}")
//        t.printStackTrace()
//    }) {
//        val tsCode = "000300.SH"
//        val lastUpdated = stockDb.transaction(IndexDailyCandleTable) {
//            IndexCandle.Companion.all().orderBy(IndexDailyCandleTable.tradeDate to SortOrder.DESC).limit(1).firstOrNull()
//        }?.tradeDate ?: "19900101"
//        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
//
//        logger.info("Fetching index daily data for $tsCode from $lastUpdated to $today")
//
//        val indexForm = tushare.getIndexDaily(
//            tsCode = tsCode,
//            startDate = lastUpdated,
//            endDate = today
//        ).check()
//
//        if (indexForm == null) {
//            logger.warning("No data returned from API")
//            return@launch
//        }
//
//        val candles = indexForm.toColumns(sortKey = "trade_date")
//        if (candles.isEmpty()) {
//            logger.info("No new data to update")
//            return@launch
//        }
//
//        logger.info("Received ${candles.size} candles, saving to database")
//
//        stockDb.transaction(IndexDailyCandleTable, log = false) {
//            IndexDailyCandleTable.batchUpsert(candles) { c ->
//                set(IndexDailyCandleTable.tsCode, c provides "ts_code")
//                set(IndexDailyCandleTable.tradeDate, c provides "trade_date")
//                set(IndexDailyCandleTable.close, (c provides "close").f)
//                set(IndexDailyCandleTable.open, (c provides "open").f)
//                set(IndexDailyCandleTable.high, (c provides "high").f)
//                set(IndexDailyCandleTable.low, (c provides "low").f)
//                set(IndexDailyCandleTable.vol, (c provides "vol").f)
//                set(IndexDailyCandleTable.amount, (c provides "amount").f)
//            }
//        }
//
//        logger.info("Successfully saved ${candles.size} candles for $tsCode")
//    }
//}
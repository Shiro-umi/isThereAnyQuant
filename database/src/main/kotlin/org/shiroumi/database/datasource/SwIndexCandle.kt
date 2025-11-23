package org.shiroumi.database.datasource

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.SwIndexDailyCandle
import org.shiroumi.database.table.SwIndexDailyCandleTable
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.TushareForm
import org.shiroumi.network.apis.getSwDaily
import org.shiroumi.network.apis.getTradingDate
import org.shiroumi.network.apis.tushare
import utils.ScheduledTasks
import utils.f
import utils.logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
suspend fun updateSwIndexDaily() = coroutineScope {
    val logger by logger("SwIndexDaily")
    launch(CoroutineExceptionHandler { _, t ->
        logger.error("Failed to update SW index daily: ${t.message}")
        t.printStackTrace()
    }) {
        logger.info("Start updating Shenwan industry index daily...")

        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        // 获取最后更新的日期
        val lastUpdated = stockDb.transaction(SwIndexDailyCandleTable) {
            SwIndexDailyCandle.all()
                .orderBy(SwIndexDailyCandleTable.tradeDate to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        }?.tradeDate ?: "19900101"

        // 获取所有需要更新的交易日，按日期倒序排列
        val dates = tushare.getTradingDate()!!.items.filter { (exchange, date, isOpen, preTradingDate) ->
            date != null && date > lastUpdated && date <= today && isOpen != "0"
        }.sortedByDescending { (exchange, date, isOpen, preTradingDate) -> date }

        logger.info("Found ${dates.size} trading days to update from $lastUpdated to $today (descending order)")

        val consecutiveEmptyCount = AtomicInt(0)
        val maxConsecutiveEmpty = 5 // 连续5次空数据就停止

        // 按日期倒序并发请求，每个日期请求所有指数的数据
        val scheduledTasks = ScheduledTasks<TushareForm?>()
        dates.forEach { (exchange, date, isOpen, preTradingDate) ->
            scheduledTasks.emit(
                tag = "$date",
                { tushare.getSwDaily(tradeDate = date).check() }
            )
        }

        scheduledTasks.schedule()
            .mapNotNull mnn@{ (tag, forms) ->
                logger.info("Processing date: $tag, forms count: ${forms.size}")
                // forms 是 List<TushareForm?>，我们只有一个任务所以取第一个
                val form = forms.firstOrNull()
                if (form == null) {
                    logger.warning("No form data for date: $tag")
                    consecutiveEmptyCount.incrementAndFetch()
                    return@mnn null
                }
                val candles = form.toColumns(sortKey = "ts_code")
                logger.info("Date $tag has ${candles.size} candles")
                if (candles.isEmpty()) {
                    consecutiveEmptyCount.incrementAndFetch()
                    return@mnn null
                }
                consecutiveEmptyCount.store(0)// 重置计数器
                tag to candles
            }
            .takeWhile {
                // 当连续空数据次数超过阈值时停止
                val shouldContinue = consecutiveEmptyCount.load() < maxConsecutiveEmpty
                if (!shouldContinue) {
                    logger.warning("Reached $maxConsecutiveEmpty consecutive empty responses, stopping...")
                }
                shouldContinue
            }
            .chunked(200)
            .collect { chunked ->
                logger.info("Writing ${chunked.size} chunks to database")
                stockDb.transaction(SwIndexDailyCandleTable, log = false) {
                    chunked.forEach { (tag, candles) ->
                        val result = SwIndexDailyCandleTable.batchUpsert(candles) { c ->
                            set(SwIndexDailyCandleTable.tsCode, c provides "ts_code")
                            set(SwIndexDailyCandleTable.tradeDate, c provides "trade_date")
                            set(SwIndexDailyCandleTable.name, c provides "name")
                            set(SwIndexDailyCandleTable.open, (c provides "open").f)
                            set(SwIndexDailyCandleTable.high, (c provides "high").f)
                            set(SwIndexDailyCandleTable.low, (c provides "low").f)
                            set(SwIndexDailyCandleTable.close, (c provides "close").f)
                            set(SwIndexDailyCandleTable.change, (c provides "change").f)
                            set(SwIndexDailyCandleTable.pctChange, (c provides "pct_change").f)
                            set(SwIndexDailyCandleTable.vol, (c provides "vol").f)
                            set(SwIndexDailyCandleTable.amount, (c provides "amount").f)
                            set(SwIndexDailyCandleTable.pe, (c provides "pe").f)
                            set(SwIndexDailyCandleTable.pb, (c provides "pb").f)
                            set(SwIndexDailyCandleTable.floatMv, (c provides "float_mv").f)
                            set(SwIndexDailyCandleTable.totalMv, (c provides "total_mv").f)
                        }
                        logger.info("Upserted ${result.size} records for $tag")
                        logger.accept(tag)
                    }
                }
            }
        logger.info("Finished updating Shenwan index daily data.")
    }
}

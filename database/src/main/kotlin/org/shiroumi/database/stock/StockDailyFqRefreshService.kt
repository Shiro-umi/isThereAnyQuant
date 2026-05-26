@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.database.stock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import model.Candle
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.stock.table.StockDailyDataTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import utils.logger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger by logger("refreshStockDailyFq")

private const val BATCH_SIZE = 200
private const val MAX_BATCH_CONCURRENCY = 4
private const val MAX_CALC_PARALLELISM = 16
private const val FQ_UPDATE_BATCH_SIZE = 10_000

@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
suspend fun refreshStockDailyFq(dates: List<kotlinx.datetime.LocalDate> = emptyList()): List<kotlinx.datetime.LocalDate> {
    try {
        val today = kotlin.time.Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        val pendingDates = if (dates.isNotEmpty()) {
            dates.distinct().sorted()
        } else {
            TradingCalendarRepository.findPendingStockDailyFqDates(today)
        }
        if (pendingDates.isEmpty()) {
            logger.info("没有待处理的复权数据更新。")
            return emptyList()
        }
        logger.info(
            "检测到待处理复权区间 | dateCount=${pendingDates.size}, startDate=${pendingDates.first()}, endDate=${pendingDates.last()}"
        )

        val tsCodes = stockDb.transaction {
            StockBasicTable.selectAll().map { it[StockBasicTable.tsCode] }.toList()
        }

        val table = StockDailyDataTable
        val changedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val batches = tsCodes.chunked(BATCH_SIZE)
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val batchConcurrency = minOf(MAX_BATCH_CONCURRENCY, maxOf(1, cpuCount / 2))
        val calcParallelism = minOf(MAX_CALC_PARALLELISM, maxOf(1, cpuCount))
        logger.info(
            "开始复权计算，共 ${tsCodes.size} 只股票，分 ${batches.size} 批处理，每批 $BATCH_SIZE 只 | " +
                "batchConcurrency=$batchConcurrency, calcParallelism=$calcParallelism"
        )

        coroutineScope {
            val semaphore = Semaphore(batchConcurrency)
            batches.mapIndexed { batchIndex, batchTsCodes ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        processBatch(
                            batchIndex = batchIndex,
                            totalBatches = batches.size,
                            tsCodes = batchTsCodes,
                            table = table,
                            changedCount = changedCount,
                            skippedCount = skippedCount,
                            calcParallelism = calcParallelism,
                        )
                    }
                }
            }.awaitAll()
        }

        logger.info("复权计算完成。需重算: ${changedCount.get()} 只，跳过(未除权): ${skippedCount.get()} 只。")
        TradingCalendarRepository.markStockDailyFqUpdated(pendingDates)
        return pendingDates
    } catch (t: Throwable) {
        logger.error("更新股票复权数据失败: ${t.message}\n${t.stackTraceToString()}")
        throw t
    }
}

private suspend fun processBatch(
    batchIndex: Int,
    totalBatches: Int,
    tsCodes: List<String>,
    table: StockDailyDataTable,
    changedCount: AtomicInteger,
    skippedCount: AtomicInteger,
    calcParallelism: Int,
) {
    val startTime = System.currentTimeMillis()
    logger.info(
        "开始处理复权批次 ${batchIndex + 1}/$totalBatches | stockCount=${tsCodes.size}, sample=${tsCodes.take(5)}"
    )
    val latestRowsStart = System.currentTimeMillis()
    val latestDataMap = loadLatestTwoRowsByStock(tsCodes)
    logger.info(
        "[DB_THROUGHPUT][FQ_LATEST_ROWS] batch=${batchIndex + 1}/$totalBatches, stocks=${tsCodes.size}, " +
            "rows=${latestDataMap.values.sumOf { it.size }}, elapsedMs=${System.currentTimeMillis() - latestRowsStart}"
    )

    val needFull = mutableListOf<String>()
    val needIncremental = mutableListOf<String>()
    val batchSkipped = mutableListOf<String>()

    tsCodes.forEach { tsCode ->
        val rows = latestDataMap[tsCode] ?: emptyList()
        if (rows.isEmpty()) {
            batchSkipped.add(tsCode)
            return@forEach
        }

        val latestRow = rows.first()
        val prevRow = rows.getOrNull(1)
        val latestAdj = latestRow.adj
        val latestHasFq = latestRow.openQfq != 0f

        if (latestHasFq) {
            batchSkipped.add(tsCode)
            return@forEach
        }

        if (prevRow == null) {
            needFull.add(tsCode)
            return@forEach
        }

        val prevAdj = prevRow.adj
        val prevHasFq = prevRow.openQfq != 0f

        if (latestAdj != prevAdj || !prevHasFq) {
            needFull.add(tsCode)
        } else {
            needIncremental.add(tsCode)
        }
    }

    skippedCount.addAndGet(batchSkipped.size)

    if (needFull.isEmpty() && needIncremental.isEmpty()) {
        logger.info(
            "批次 ${batchIndex + 1}/$totalBatches 无需更新 | skipped=${batchSkipped.size}, elapsed=${System.currentTimeMillis() - startTime}ms"
        )
        return
    }

    var updatedRowCount = 0
    if (needFull.isNotEmpty()) {
        val fullCandlesMap = stockDb.transaction {
            table.selectAll()
                .where { table.tsCode inList needFull }
                .orderBy(table.tradeDate)
                .map { rowToCandle(it) }
        }.groupBy { it.tsCode }

        val fullCalculatedRows = coroutineScope {
            fullCandlesMap.map { (tsCode, candles) ->
                async(Dispatchers.Default.limitedParallelism(calcParallelism)) {
                    calculateFqRows(tsCode, candles)
                }
            }.awaitAll().flatten()
        }

        updateFqRows(fullCalculatedRows, batchIndex, totalBatches, "FULL")
        changedCount.addAndGet(needFull.size)
        updatedRowCount += fullCalculatedRows.size
        logger.info(
            "批次 ${batchIndex + 1}/$totalBatches: 全量重算 ${needFull.size} 只, updatedRows=${fullCalculatedRows.size}"
        )
    }

    if (needIncremental.isNotEmpty()) {
        val latestAdjMap = latestDataMap.filter { it.key in needIncremental }
            .mapValues { it.value.first().adj }

        val incrementalCandlesMap = stockDb.transaction {
            table.selectAll()
                .where { (table.tsCode inList needIncremental) and (table.openQfq eq 0f) }
                .orderBy(table.tradeDate)
                .map { rowToCandle(it) }
        }.groupBy { it.tsCode }

        val incrementalCalculatedRows = coroutineScope {
            incrementalCandlesMap.map { (tsCode, candles) ->
                async(Dispatchers.Default.limitedParallelism(calcParallelism)) {
                    val latestAdj = latestAdjMap[tsCode] ?: candles.last().adj
                    calculateFqRows(tsCode, candles, overrideLatestAdj = latestAdj)
                }
            }.awaitAll().flatten()
        }

        updateFqRows(incrementalCalculatedRows, batchIndex, totalBatches, "INCREMENTAL")
        changedCount.addAndGet(needIncremental.size)
        updatedRowCount += incrementalCalculatedRows.size
        logger.info(
            "批次 ${batchIndex + 1}/$totalBatches: 增量更新 ${needIncremental.size} 只, updatedRows=${incrementalCalculatedRows.size}"
        )
    }

    val elapsed = System.currentTimeMillis() - startTime
    if (needFull.isNotEmpty() || needIncremental.isNotEmpty()) {
        val progress = (((batchIndex + 1).toDouble() / totalBatches.toDouble()) * 100).toInt()
        logger.info(
            "批次 ${batchIndex + 1}/$totalBatches 完成 | progress=${progress}%, full=${needFull.size}, incremental=${needIncremental.size}, skipped=${batchSkipped.size}, totalUpdatedRows=$updatedRowCount, elapsed=${elapsed}ms"
        )
    }
}

private fun loadLatestTwoRowsByStock(tsCodes: List<String>): Map<String, List<StockLatestData>> {
    if (tsCodes.isEmpty()) return emptyMap()
    val quotedTsCodes = tsCodes.distinct().joinToString(",") { it.sqlQuote() }
    return stockDb.transaction(log = false) {
        val sql = """
            SELECT ts_code, trade_date, adj, open_qfq
            FROM (
                SELECT d.ts_code,
                       d.trade_date,
                       d.adj,
                       d.open_qfq,
                       ROW_NUMBER() OVER (
                           PARTITION BY d.ts_code
                           ORDER BY d.trade_date DESC
                       ) AS rn
                FROM stock_daily_data d
                WHERE d.ts_code IN ($quotedTsCodes)
            ) ranked
            WHERE ranked.rn <= 2
            ORDER BY ranked.ts_code ASC, ranked.trade_date DESC
        """.trimIndent()
        val result = linkedMapOf<String, MutableList<StockLatestData>>()
        exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
            while (rs.next()) {
                val row = StockLatestData(
                    tsCode = rs.getString("ts_code"),
                    adj = rs.getFloat("adj"),
                    openQfq = rs.getFloat("open_qfq"),
                    tradeDate = kotlinx.datetime.LocalDate.parse(rs.getString("trade_date")),
                )
                result.getOrPut(row.tsCode) { mutableListOf() }.add(row)
            }
            true
        }
        result
    }
}

private fun String.sqlQuote(): String = "'${replace("'", "''")}'"

private data class StockLatestData(
    val tsCode: String,
    val adj: Float,
    val openQfq: Float,
    val tradeDate: kotlinx.datetime.LocalDate
)

private fun rowToCandle(row: ResultRow): Candle {
    return Candle(
        tsCode = row[StockDailyDataTable.tsCode],
        date = row[StockDailyDataTable.tradeDate],
        turnoverReal = row[StockDailyDataTable.turnoverReal],
        pe = row[StockDailyDataTable.pe],
        peTtm = row[StockDailyDataTable.peTtm],
        pb = row[StockDailyDataTable.pb],
        ps = row[StockDailyDataTable.ps],
        psTtm = row[StockDailyDataTable.psTtm],
        mvTotal = row[StockDailyDataTable.mvTotal],
        mvCirc = row[StockDailyDataTable.mvCirc],
        open = row[StockDailyDataTable.open],
        high = row[StockDailyDataTable.high],
        low = row[StockDailyDataTable.low],
        close = row[StockDailyDataTable.close],
        volume = row[StockDailyDataTable.volume],
        adj = row[StockDailyDataTable.adj],
        openQfq = row[StockDailyDataTable.openQfq],
        highQfq = row[StockDailyDataTable.highQfq],
        lowQfq = row[StockDailyDataTable.lowQfq],
        closeQfq = row[StockDailyDataTable.closeQfq],
        volumeQfq = row[StockDailyDataTable.volumeQfq]
    )
}

private fun calculateFqRows(
    tsCode: String,
    candles: List<Candle>,
    overrideLatestAdj: Float? = null
): List<Candle> {
    if (candles.isEmpty()) return emptyList()
    val latestAdj = overrideLatestAdj ?: candles.last().adj
    return candles.map { candle ->
        val factor = candle.adj / latestAdj
        candle.copy(
            tsCode = tsCode,
            openQfq = factor * candle.open,
            highQfq = factor * candle.high,
            lowQfq = factor * candle.low,
            closeQfq = factor * candle.close,
            volumeQfq = (latestAdj / candle.adj) * candle.volume
        )
    }
}

private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.batchUpdateFq(
    rows: List<Candle>
) {
    if (rows.isEmpty()) return

    val sql = """
        UPDATE stock_daily_data
        SET open_qfq = ?, high_qfq = ?, low_qfq = ?, close_qfq = ?, volume_qfq = ?
        WHERE ts_code = ? AND trade_date = ?
    """.trimIndent()

    val columnType = VarCharColumnType()
    connection.prepareStatement(sql, false).let { statement ->
        rows.forEach { row ->
            statement.set(1, row.openQfq, columnType)
            statement.set(2, row.highQfq, columnType)
            statement.set(3, row.lowQfq, columnType)
            statement.set(4, row.closeQfq, columnType)
            statement.set(5, row.volumeQfq, columnType)
            statement.set(6, row.tsCode, columnType)
            statement.set(7, java.sql.Date.valueOf(row.date.toString()), columnType)
            statement.addBatch()
        }
        statement.executeBatch()
        statement.closeIfPossible()
    }
}

private fun updateFqRows(
    rows: List<Candle>,
    batchIndex: Int,
    totalBatches: Int,
    mode: String,
) {
    if (rows.isEmpty()) return
    val chunks = rows.chunked(FQ_UPDATE_BATCH_SIZE)
    chunks.forEachIndexed { updateBatchIndex, chunk ->
        val updateStart = System.currentTimeMillis()
        stockDb.transaction(StockDailyDataTable, log = false) {
            batchUpdateFq(chunk)
        }
        val elapsed = System.currentTimeMillis() - updateStart
        val rowsPerSecond = if (elapsed > 0) chunk.size * 1000.0 / elapsed else chunk.size.toDouble()
        logger.info(
            "[DB_THROUGHPUT][FQ_UPDATE] mode=$mode, rows=${chunk.size}, elapsedMs=$elapsed, " +
                "rowsPerSecond=${"%.2f".format(rowsPerSecond)}, " +
                "fqBatch=${batchIndex + 1}/$totalBatches, updateBatch=${updateBatchIndex + 1}/${chunks.size}"
        )
    }
}

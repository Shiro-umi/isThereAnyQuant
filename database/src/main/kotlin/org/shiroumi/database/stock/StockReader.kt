package org.shiroumi.database.stock

import model.Candle
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.stock.table.StockDailyDataTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import utils.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import org.shiroumi.database.common.CalendarReader
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object StockReader {
    private val logger by logger("StockReader")

    private val firstAdjCache = ConcurrentHashMap<String, Float>()

    data class StockBasicRecord(
        val tsCode: String,
        val name: String,
        val cnSpell: String,
        val market: String,
        val listStatus: String
    )

    fun getStockInfoMap(): Map<String, String> {
        return stockDb.transaction(log = false) {
            StockBasicTable.selectAll()
                .associate {
                    it[StockBasicTable.tsCode] to it[StockBasicTable.name]
                }
        }
    }

    fun getAllStockBasics(): List<StockBasicRecord> {
        return stockDb.transaction(log = false) {
            StockBasicTable.selectAll()
                .map {
                    StockBasicRecord(
                        tsCode = it[StockBasicTable.tsCode],
                        name = it[StockBasicTable.name],
                        cnSpell = it[StockBasicTable.cnSpell],
                        market = it[StockBasicTable.market],
                        listStatus = it[StockBasicTable.listStatus]
                    )
                }
                .sortedBy { it.tsCode }
        }
    }

    fun getAllSymbols(): List<String> {
        return stockDb.transaction(log = false) {
            StockBasicTable.selectAll()
                .map { it[StockBasicTable.tsCode] }
                .sorted()
        }
    }

    fun getStockName(tsCode: String): String? {
        return stockDb.transaction(log = false) {
            StockBasicTable.selectAll()
                .where { StockBasicTable.tsCode eq tsCode }
                .singleOrNull()
                ?.get(StockBasicTable.name)
        }
    }

    fun getStockNames(tsCodes: Collection<String>): Map<String, String> {
        if (tsCodes.isEmpty()) return emptyMap()

        val distinctCodes = tsCodes.distinct()
        return stockDb.transaction(log = false) {
            StockBasicTable.selectAll()
                .where { StockBasicTable.tsCode inList distinctCodes }
                .associate {
                    it[StockBasicTable.tsCode] to it[StockBasicTable.name]
                }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getStockHistory(tsCode: String, limit: Int = 30): List<Candle> {
        return try {
            stockDb.transaction(StockDailyDataTable, log = false) {
                StockDailyDataTable.selectAll()
                    .where { StockDailyDataTable.tsCode eq tsCode }
                    .orderBy(StockDailyDataTable.tradeDate, SortOrder.DESC)
                    .limit(limit)
                    .map { it.toCandle() }
                    .reversed()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getStockHistory(tsCode: String, startDate: kotlinx.datetime.LocalDate, endDate: kotlinx.datetime.LocalDate): List<Candle> {
        return try {
            stockDb.transaction(StockDailyDataTable, log = false) {
                StockDailyDataTable.selectAll()
                    .where { 
                        (StockDailyDataTable.tsCode eq tsCode) and 
                        (StockDailyDataTable.tradeDate greaterEq startDate) and 
                        (StockDailyDataTable.tradeDate lessEq endDate) 
                    }
                    .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                    .map { it.toCandle() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 批量获取多只股票各自最近 N 条K线数据
     * 用于股票列表页展示最新价格，避免 N+1 查询
     */
    @OptIn(ExperimentalUuidApi::class)
    fun getBatchLatestCandles(tsCodes: List<String>, limit: Int = 2): Map<String, List<Candle>> {
        if (tsCodes.isEmpty()) return emptyMap()
        if (limit <= 0) return emptyMap()

        return try {
            stockDb.transaction(log = false) {
                val result = linkedMapOf<String, MutableList<Candle>>()
                tsCodes.distinct().chunked(200).forEach { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    val sql = """
                        SELECT *
                        FROM (
                            SELECT d.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY d.ts_code
                                       ORDER BY d.trade_date DESC
                                   ) AS rn
                            FROM stock_daily_data d
                            WHERE d.ts_code IN ($placeholders)
                        ) ranked
                        WHERE ranked.rn <= ?
                        ORDER BY ranked.ts_code ASC, ranked.trade_date ASC
                    """.trimIndent()
                    val args = chunk.map { VarCharColumnType() to it } + (IntegerColumnType() to limit)
                    exec(sql, args = args, explicitStatementType = StatementType.SELECT) { rs ->
                        while (rs.next()) {
                            val candle = rs.toDailyCandle()
                            result.getOrPut(candle.tsCode) { mutableListOf() }.add(candle)
                        }
                        true
                    }
                }
                result
            }
        } catch (e: Exception) {
            logger.error("批量加载最新日线失败: requested=${tsCodes.size}, limit=$limit, error=${e.message}")
            emptyMap()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getAllStockDailyWindows(limit: Int = 500): Map<String, List<Candle>> {
        if (limit <= 0) return emptyMap()

        return try {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            // 取 limit * 2 作为安全余量 (约能容纳极长停牌期的股票)，以大幅减少数据库全表排序的数据量
            val bufferDays = (limit * 2).coerceAtLeast(100)
            val recentDays = CalendarReader.getRecentTradingDays(today, bufferDays)
            val cutoffDate = recentDays.lastOrNull()?.toString() ?: "2000-01-01"

            stockDb.transaction(log = false) {
                val sql = """
                    WITH
                    ranked_daily AS (
                        SELECT d.*,
                               ROW_NUMBER() OVER (
                                   PARTITION BY d.ts_code
                                   ORDER BY d.trade_date DESC
                               ) AS rn
                        FROM stock_daily_data d
                        WHERE d.trade_date >= '$cutoffDate'
                    )
                    SELECT *
                    FROM ranked_daily
                    WHERE rn <= $limit
                    ORDER BY ts_code ASC, trade_date ASC
                """.trimIndent()

                val result = linkedMapOf<String, MutableList<Candle>>()
                val sqlStart = System.currentTimeMillis()
                var rowCount = 0
                exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
                    while (rs.next()) {
                        rowCount++
                        val candle = rs.toDailyCandle()
                        result.getOrPut(candle.tsCode) { mutableListOf() }.add(candle)
                    }
                    true
                }
                val sqlElapsed = System.currentTimeMillis() - sqlStart
                val rowsPerSecond = if (sqlElapsed > 0) rowCount * 1000.0 / sqlElapsed else rowCount.toDouble()
                logger.info(
                    "[DB_THROUGHPUT][DAY_WARMUP] rows=$rowCount, stocks=${result.size}, elapsedMs=$sqlElapsed, " +
                        "rowsPerSecond=${"%.2f".format(rowsPerSecond)}, cutoff=$cutoffDate, limit=$limit"
                )
                result
            }
        } catch (e: Exception) {
            logger.error("加载全市场日线窗口失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 【新增核心优化】批量获取多只股票的历史数据
     * 消除 N+1 查询瓶颈
     */
    @OptIn(ExperimentalUuidApi::class)
    fun getBatchStockHistory(
        tsCodes: List<String>,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): Map<String, List<Candle>> {
        if (tsCodes.isEmpty()) return emptyMap()

        return try {
            stockDb.transaction(StockDailyDataTable, log = false) {
                StockDailyDataTable.selectAll()
                    .where {
                        (StockDailyDataTable.tsCode inList tsCodes) and
                        (StockDailyDataTable.tradeDate greaterEq startDate) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                    }
                    .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                    .map { it.toCandle() }
                    .groupBy { it.tsCode }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float> {
        if (tsCodes.isEmpty()) return emptyMap()

        val requestedCodes = tsCodes.distinct()
        val result = mutableMapOf<String, Float>()
        val missing = requestedCodes.filterNot { tsCode ->
            firstAdjCache[tsCode]?.let {
                result[tsCode] = it
                true
            } ?: false
        }

        if (missing.isEmpty()) {
            logger.info("[FIRST_ADJ][CACHE_HIT] requested=${requestedCodes.size}, returned=${result.size}, cacheHits=${result.size}, missing=0")
            return result
        }

        val loaded = try {
            stockDb.transaction(StockDailyDataTable, log = false) {
                val out = mutableMapOf<String, Float>()
                missing.chunked(200).forEach { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    val sql = """
                        SELECT s.ts_code, s.adj
                        FROM stock_daily_data s
                        INNER JOIN (
                            SELECT ts_code, MIN(trade_date) AS min_trade_date
                            FROM stock_daily_data
                            WHERE ts_code IN ($placeholders)
                            GROUP BY ts_code
                        ) first_rows
                        ON s.ts_code = first_rows.ts_code
                        AND s.trade_date = first_rows.min_trade_date
                    """.trimIndent()

                    val args = chunk.map { VarCharColumnType() to it }
                    exec(sql, args = args, explicitStatementType = StatementType.SELECT) { rs ->
                        while (rs.next()) {
                            out[rs.getString("ts_code")] = rs.getFloat("adj")
                        }
                        true
                    }
                }
                out
            }
        } catch (e: Exception) {
            logger.error(
                "[FIRST_ADJ][PIPELINE_PARTIAL_FIRST_ADJ_MAP] requested=${requestedCodes.size}, " +
                    "cacheHits=${result.size}, dbRequested=${missing.size}, error=${e.message}"
            )
            emptyMap()
        }

        loaded.forEach { (tsCode, firstAdj) ->
            firstAdjCache[tsCode] = firstAdj
            result[tsCode] = firstAdj
        }

        val unresolved = missing.filterNot { loaded.containsKey(it) }
        if (unresolved.isNotEmpty()) {
            logger.warning(
                "[FIRST_ADJ][SOURCE_MISSING_FIRST_ADJ] requested=${requestedCodes.size}, " +
                    "cacheHits=${requestedCodes.size - missing.size}, dbRequested=${missing.size}, " +
                    "dbReturned=${loaded.size}, unresolved=${unresolved.size}, sample=${unresolved.take(20).joinToString(",")}"
            )
        } else {
            logger.info(
                "[FIRST_ADJ][DB_LOAD_OK] requested=${requestedCodes.size}, " +
                    "cacheHits=${requestedCodes.size - missing.size}, dbRequested=${missing.size}, dbReturned=${loaded.size}, unresolved=0"
            )
        }

        return result
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun ResultRow.toCandle(): Candle {
        val table = StockDailyDataTable
        return Candle(
            tsCode = this[table.tsCode],
            date = this[table.tradeDate],
            open = this[table.open],
            high = this[table.high],
            low = this[table.low],
            close = this[table.close],
            adj = this[table.adj],
            openQfq = this[table.openQfq],
            closeQfq = this[table.closeQfq],
            highQfq = this[table.highQfq],
            lowQfq = this[table.lowQfq],
            volume = this[table.volume],
            volumeQfq = this[table.volumeQfq],
            turnoverReal = this[table.turnoverReal],
            pe = this[table.pe],
            peTtm = this[table.peTtm],
            pb = this[table.pb],
            ps = this[table.ps],
            psTtm = this[table.psTtm],
            mvTotal = this[table.mvTotal],
            mvCirc = this[table.mvCirc]
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun java.sql.ResultSet.toDailyCandle(): Candle {
    return Candle(
        tsCode = getString("ts_code"),
        date = kotlinx.datetime.LocalDate.parse(getDate("trade_date").toString()),
        open = getFloat("open"),
        high = getFloat("high"),
        low = getFloat("low"),
        close = getFloat("close"),
        adj = getFloat("adj"),
        openQfq = getFloat("open_qfq"),
        closeQfq = getFloat("close_qfq"),
        highQfq = getFloat("high_qfq"),
        lowQfq = getFloat("low_qfq"),
        volume = getFloat("volume"),
        volumeQfq = getFloat("volume_qfq"),
        turnoverReal = getFloat("turnover_real"),
        pe = getFloat("pe"),
        peTtm = getFloat("pe_ttm"),
        pb = getFloat("pb"),
        ps = getFloat("ps"),
        psTtm = getFloat("ps_ttm"),
        mvTotal = getFloat("mv_total"),
        mvCirc = getFloat("mv_circ")
    )
}

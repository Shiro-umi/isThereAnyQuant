package org.shiroumi.database.stock

import model.Candle
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.StockDailyDataTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import utils.logger
import kotlin.uuid.ExperimentalUuidApi

private val stockDailyCandleLogger by logger("StockDailyCandleRepository")
private const val RAW_DAILY_UPSERT_BATCH_SIZE = 10_000
private const val FULL_DAILY_UPSERT_BATCH_SIZE = 10_000

/**
 * 新架构对日线持久化的稳定访问边界。
 *
 * 这个 Repository 的职责非常明确：
 * 1. 接收已经由 Tushare 日线接口族标准化后的 `Candle`
 * 2. 把这些日线数据写入 `stock_daily_data`
 * 3. 以稳定查询接口向新架构返回历史日线 H 轨道
 *
 * 重要约束：
 * - 它只处理日线数据
 * - 分钟/周/月历史不通过这里持久化
 * - `ktor-server` 只允许通过这个边界访问日线持久化，不允许自己直接操纵表事务
 */
object StockDailyCandleRepository {

    fun hasAnyDailyFacts(): Boolean {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .limit(1)
                .any()
        }
    }

    fun existsForTradeDate(tradeDate: kotlinx.datetime.LocalDate): Boolean {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .where { StockDailyDataTable.tradeDate eq tradeDate }
                .limit(1)
                .map { true }
                .firstOrNull() ?: false
        }
    }

    fun findExistingTradeDates(fromInclusive: kotlinx.datetime.LocalDate, toInclusive: kotlinx.datetime.LocalDate): Set<kotlinx.datetime.LocalDate> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable
                .select(StockDailyDataTable.tradeDate)
                .where {
                    (StockDailyDataTable.tradeDate greaterEq fromInclusive) and
                        (StockDailyDataTable.tradeDate lessEq toInclusive)
                }
                .withDistinct()
                .mapTo(linkedSetOf()) { it[StockDailyDataTable.tradeDate] }
        }
    }

    fun findPendingFqTradeDates(fromInclusive: kotlinx.datetime.LocalDate, toInclusive: kotlinx.datetime.LocalDate): Set<kotlinx.datetime.LocalDate> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable
                .select(StockDailyDataTable.tradeDate)
                .where {
                    (StockDailyDataTable.tradeDate greaterEq fromInclusive) and
                        (StockDailyDataTable.tradeDate lessEq toInclusive) and
                        (StockDailyDataTable.openQfq eq 0f)
                }
                .withDistinct()
                .mapTo(linkedSetOf()) { it[StockDailyDataTable.tradeDate] }
        }
    }

    /**
     * 批量写入"原始日线事实"（原地 upsert）。
     *
     * 这个写口专门服务于盘后按交易日批处理同步：
     * - 只写 Tushare 当日原始事实字段
     * - 复权字段统一回到默认值 0，等待后续 `refreshStockDailyFq()` 重算
     *
     * 使用 `batchUpsert` 替代原先的 `batchReplace`：
     * - `batchReplace` 本质是 DELETE + INSERT，对已存在行做两次 IO
     * - `batchUpsert` 生成 INSERT ... ON DUPLICATE KEY UPDATE，原地更新，只做一次写
     * - 无需指定 keys：MySQL 自动基于表上的 `uk_code_date` 唯一索引检测冲突
     * - JDBC URL 已配置 `rewriteBatchedStatements=true`，多行合并为单次网络往返
     */
    fun replaceRawDailyFacts(candles: List<Candle>) {
        if (candles.isEmpty()) return

        val totalStart = System.currentTimeMillis()
        var totalRows = 0
        val batches = candles.chunked(RAW_DAILY_UPSERT_BATCH_SIZE)
        batches.forEachIndexed { batchIndex, batch ->
            val batchStart = System.currentTimeMillis()
            stockDb.transaction(StockDailyDataTable, log = false) {
                StockDailyDataTable.batchUpsert(batch) { candle ->
                    this[StockDailyDataTable.tsCode] = candle.tsCode
                    this[StockDailyDataTable.tradeDate] = candle.date
                    this[StockDailyDataTable.open] = candle.open
                    this[StockDailyDataTable.high] = candle.high
                    this[StockDailyDataTable.low] = candle.low
                    this[StockDailyDataTable.close] = candle.close
                    this[StockDailyDataTable.adj] = candle.adj
                    this[StockDailyDataTable.openQfq] = 0f
                    this[StockDailyDataTable.closeQfq] = 0f
                    this[StockDailyDataTable.highQfq] = 0f
                    this[StockDailyDataTable.lowQfq] = 0f
                    this[StockDailyDataTable.volume] = candle.volume
                    this[StockDailyDataTable.volumeQfq] = 0f
                    this[StockDailyDataTable.turnoverReal] = candle.turnoverReal
                    this[StockDailyDataTable.pe] = candle.pe
                    this[StockDailyDataTable.peTtm] = candle.peTtm
                    this[StockDailyDataTable.pb] = candle.pb
                    this[StockDailyDataTable.ps] = candle.ps
                    this[StockDailyDataTable.psTtm] = candle.psTtm
                    this[StockDailyDataTable.mvTotal] = candle.mvTotal
                    this[StockDailyDataTable.mvCirc] = candle.mvCirc
                }
            }
            totalRows += batch.size
            logThroughput(
                tag = "RAW_DAILY_UPSERT",
                rows = batch.size,
                elapsedMs = System.currentTimeMillis() - batchStart,
                batchIndex = batchIndex,
                totalBatches = batches.size,
            )
        }
        logThroughput(
            tag = "RAW_DAILY_UPSERT_TOTAL",
            rows = totalRows,
            elapsedMs = System.currentTimeMillis() - totalStart,
            batchIndex = 0,
            totalBatches = 1,
        )
    }

    private fun upsertFullCandles(batch: List<Candle>) {
        stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.batchUpsert(batch) { candle ->
                this[StockDailyDataTable.tsCode] = candle.tsCode
                this[StockDailyDataTable.tradeDate] = candle.date
                this[StockDailyDataTable.open] = candle.open
                this[StockDailyDataTable.high] = candle.high
                this[StockDailyDataTable.low] = candle.low
                this[StockDailyDataTable.close] = candle.close
                this[StockDailyDataTable.adj] = candle.adj
                this[StockDailyDataTable.openQfq] = candle.openQfq
                this[StockDailyDataTable.closeQfq] = candle.closeQfq
                this[StockDailyDataTable.highQfq] = candle.highQfq
                this[StockDailyDataTable.lowQfq] = candle.lowQfq
                this[StockDailyDataTable.volume] = candle.volume
                this[StockDailyDataTable.volumeQfq] = candle.volumeQfq
                this[StockDailyDataTable.turnoverReal] = candle.turnoverReal
                this[StockDailyDataTable.pe] = candle.pe
                this[StockDailyDataTable.peTtm] = candle.peTtm
                this[StockDailyDataTable.pb] = candle.pb
                this[StockDailyDataTable.ps] = candle.ps
                this[StockDailyDataTable.psTtm] = candle.psTtm
                this[StockDailyDataTable.mvTotal] = candle.mvTotal
                this[StockDailyDataTable.mvCirc] = candle.mvCirc
            }
        }
    }

    /**
     * 批量写入指定股票的完整日线数据（含复权字段）。
     *
     * 使用 `batchUpsert` 与表的 `uk_code_date` 唯一索引配合，保证：
     * - 同一交易日重复同步时幂等覆盖（原地更新，非 DELETE + INSERT）
     * - 不会产生重复日线
     */
    fun replaceCandles(candles: List<Candle>) {
        if (candles.isEmpty()) return

        val totalStart = System.currentTimeMillis()
        val batches = candles.chunked(FULL_DAILY_UPSERT_BATCH_SIZE)
        batches.forEachIndexed { batchIndex, batch ->
            val batchStart = System.currentTimeMillis()
            upsertFullCandles(batch)
            logThroughput(
                tag = "FULL_DAILY_UPSERT",
                rows = batch.size,
                elapsedMs = System.currentTimeMillis() - batchStart,
                batchIndex = batchIndex,
                totalBatches = batches.size,
            )
        }
        logThroughput(
            tag = "FULL_DAILY_UPSERT_TOTAL",
            rows = candles.size,
            elapsedMs = System.currentTimeMillis() - totalStart,
            batchIndex = 0,
            totalBatches = 1,
        )
    }

    /**
     * 读取最近 N 条日线。
     */
    fun findRecent(
        tsCode: String,
        limit: Int,
        endDateInclusive: kotlinx.datetime.LocalDate? = null
    ): List<Candle> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .where {
                    if (endDateInclusive != null) {
                        (StockDailyDataTable.tsCode eq tsCode) and
                            (StockDailyDataTable.tradeDate lessEq endDateInclusive)
                    } else {
                        StockDailyDataTable.tsCode eq tsCode
                    }
                }
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.DESC)
                .limit(limit)
                .map { it.toCandle() }
                .reversed()
        }
    }

    /**
     * 按日期区间读取日线。
     */
    fun findRange(
        tsCode: String,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): List<Candle> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .where {
                    (StockDailyDataTable.tsCode eq tsCode) and
                        (StockDailyDataTable.tradeDate greaterEq startDate) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                }
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .map { it.toCandle() }
        }
    }

    fun findByTradeDate(tradeDate: kotlinx.datetime.LocalDate): List<Candle> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .where { StockDailyDataTable.tradeDate eq tradeDate }
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .map { it.toCandle() }
        }
    }

    fun findAdjByTradeDate(
        tradeDate: kotlinx.datetime.LocalDate,
        tsCodes: List<String>
    ): Map<String, Float> {
        if (tsCodes.isEmpty()) return emptyMap()

        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable
                .select(StockDailyDataTable.tsCode, StockDailyDataTable.adj)
                .where {
                    (StockDailyDataTable.tradeDate eq tradeDate) and
                        (StockDailyDataTable.tsCode inList tsCodes.distinct())
                }
                .associate { row ->
                    row[StockDailyDataTable.tsCode] to row[StockDailyDataTable.adj]
                }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun ResultRow.toCandle(): Candle {
        return Candle(
            tsCode = this[StockDailyDataTable.tsCode],
            date = this[StockDailyDataTable.tradeDate],
            open = this[StockDailyDataTable.open],
            high = this[StockDailyDataTable.high],
            low = this[StockDailyDataTable.low],
            close = this[StockDailyDataTable.close],
            adj = this[StockDailyDataTable.adj],
            openQfq = this[StockDailyDataTable.openQfq],
            closeQfq = this[StockDailyDataTable.closeQfq],
            highQfq = this[StockDailyDataTable.highQfq],
            lowQfq = this[StockDailyDataTable.lowQfq],
            volume = this[StockDailyDataTable.volume],
            volumeQfq = this[StockDailyDataTable.volumeQfq],
            turnoverReal = this[StockDailyDataTable.turnoverReal],
            pe = this[StockDailyDataTable.pe],
            peTtm = this[StockDailyDataTable.peTtm],
            pb = this[StockDailyDataTable.pb],
            ps = this[StockDailyDataTable.ps],
            psTtm = this[StockDailyDataTable.psTtm],
            mvTotal = this[StockDailyDataTable.mvTotal],
            mvCirc = this[StockDailyDataTable.mvCirc]
        )
    }

    private fun logThroughput(
        tag: String,
        rows: Int,
        elapsedMs: Long,
        batchIndex: Int,
        totalBatches: Int,
    ) {
        val rowsPerSecond = if (elapsedMs > 0) rows * 1000.0 / elapsedMs else rows.toDouble()
        stockDailyCandleLogger.info(
            "[DB_THROUGHPUT][$tag] rows=$rows, elapsedMs=$elapsedMs, rowsPerSecond=${"%.2f".format(rowsPerSecond)}, " +
                "batch=${batchIndex + 1}/$totalBatches"
        )
    }
}

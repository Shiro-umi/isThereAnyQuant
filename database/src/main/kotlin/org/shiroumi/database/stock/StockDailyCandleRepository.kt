package org.shiroumi.database.stock

import model.Candle
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
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

/** 全市场前复权收盘价的轻量投影（H1 行业聚合用，避免跨模块传完整 Candle）。
 *  peTtm 供 H3 估值二维分层用；≤0 表示缺失/亏损（PE 为负），由上层判 null 处理。
 *  turnover 供拥挤度中介验证用（换手率作拥挤度代理）；≤0 视缺失。
 *  mvCirc 流通市值，供「换手率拥挤 vs 小盘效应」去伪验证用；≤0 视缺失。 */
data class DailyCloseProjection(
    val tsCode: String,
    val tradeDate: kotlinx.datetime.LocalDate,
    val closeQfq: Double,
    val peTtm: Double? = null,
    val turnover: Double? = null,
    val mvCirc: Double? = null,
)

/** 全市场日线 OHLCV 研究投影（profit-prediction 输入导出用）。 */
data class DailyOhlcvProjection(
    val tsCode: String,
    val tradeDate: kotlinx.datetime.LocalDate,
    val openQfq: Double,
    val highQfq: Double,
    val lowQfq: Double,
    val closeQfq: Double,
    val volumeQfq: Double,
    val turnoverReal: Double,
)

data class ProductionOhlcvWindowRow(
    val tsCode: String,
    val tradeDate: kotlinx.datetime.LocalDate,
    val openQfq: Double,
    val highQfq: Double,
    val lowQfq: Double,
    val closeQfq: Double,
    val volumeQfq: Double,
    val turnoverReal: Double,
    val adjustedComplete: Boolean,
)

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

    /**
     * 按日期区间批量查询全市场日线蜡烛，返回按 [tradeDate] 分组的结果。
     *
     * 回测预加载用——单次 DB 查询替代逐日 N 次查询。
     */
    fun findByDateRange(
        from: kotlinx.datetime.LocalDate,
        to: kotlinx.datetime.LocalDate,
    ): Map<kotlinx.datetime.LocalDate, List<Candle>> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable.selectAll()
                .where {
                    (StockDailyDataTable.tradeDate greaterEq from) and
                        (StockDailyDataTable.tradeDate lessEq to)
                }
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .map { it.toCandle() }
                .groupBy { it.date }
        }
    }

    /**
     * 全市场前复权收盘价轻量投影（factor topic · H1 行业聚合研究用）。
     *
     * 只 select (ts_code, trade_date, close_qfq) 三列，按 ts_code+date 排序，
     * 供应用层逐股算日收益再按行业等权聚合，避免把完整 Candle 列表跨模块边界传输。
     * closeQfq ≤ 0 时回退 close。
     */
    fun streamCloseForAggregation(
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): List<DailyCloseProjection> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            StockDailyDataTable
                .select(StockDailyDataTable.tsCode, StockDailyDataTable.tradeDate, StockDailyDataTable.closeQfq, StockDailyDataTable.close, StockDailyDataTable.peTtm, StockDailyDataTable.turnoverReal, StockDailyDataTable.mvCirc)
                .where {
                    (StockDailyDataTable.tradeDate greaterEq startDate) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                }
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .map { row ->
                    val cq = row[StockDailyDataTable.closeQfq].toDouble()
                    val pe = row[StockDailyDataTable.peTtm].toDouble()
                    val tnr = row[StockDailyDataTable.turnoverReal].toDouble()
                    val mv = row[StockDailyDataTable.mvCirc].toDouble()
                    DailyCloseProjection(
                        tsCode = row[StockDailyDataTable.tsCode],
                        tradeDate = row[StockDailyDataTable.tradeDate],
                        closeQfq = if (cq > 0.0) cq else row[StockDailyDataTable.close].toDouble(),
                        peTtm = if (pe > 0.0) pe else null,  // PE≤0（亏损）视为缺失
                        turnover = if (tnr > 0.0) tnr else null,
                        mvCirc = if (mv > 0.0) mv else null,
                    )
                }
        }
    }

    fun streamCloseForAggregationPage(
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate,
        afterTsCode: String?,
        afterTradeDate: kotlinx.datetime.LocalDate?,
        limit: Int,
    ): List<DailyCloseProjection> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            val cursor = if (afterTsCode != null && afterTradeDate != null) {
                (StockDailyDataTable.tsCode greater afterTsCode) or
                    ((StockDailyDataTable.tsCode eq afterTsCode) and (StockDailyDataTable.tradeDate greater afterTradeDate))
            } else {
                null
            }
            StockDailyDataTable
                .select(StockDailyDataTable.tsCode, StockDailyDataTable.tradeDate, StockDailyDataTable.closeQfq, StockDailyDataTable.close, StockDailyDataTable.peTtm, StockDailyDataTable.turnoverReal, StockDailyDataTable.mvCirc)
                .where {
                    val range = (StockDailyDataTable.tradeDate greaterEq startDate) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                    if (cursor == null) range else range and cursor
                }
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    val cq = row[StockDailyDataTable.closeQfq].toDouble()
                    val pe = row[StockDailyDataTable.peTtm].toDouble()
                    val tnr = row[StockDailyDataTable.turnoverReal].toDouble()
                    val mv = row[StockDailyDataTable.mvCirc].toDouble()
                    DailyCloseProjection(
                        tsCode = row[StockDailyDataTable.tsCode],
                        tradeDate = row[StockDailyDataTable.tradeDate],
                        closeQfq = if (cq > 0.0) cq else row[StockDailyDataTable.close].toDouble(),
                        peTtm = if (pe > 0.0) pe else null,
                        turnover = if (tnr > 0.0) tnr else null,
                        mvCirc = if (mv > 0.0) mv else null,
                    )
                }
        }
    }

    fun streamOhlcvForResearchPage(
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate,
        afterTsCode: String?,
        afterTradeDate: kotlinx.datetime.LocalDate?,
        limit: Int,
    ): List<DailyOhlcvProjection> {
        return stockDb.transaction(StockDailyDataTable, log = false) {
            val cursor = if (afterTsCode != null && afterTradeDate != null) {
                (StockDailyDataTable.tsCode greater afterTsCode) or
                    ((StockDailyDataTable.tsCode eq afterTsCode) and (StockDailyDataTable.tradeDate greater afterTradeDate))
            } else {
                null
            }
            StockDailyDataTable
                .select(
                    StockDailyDataTable.tsCode,
                    StockDailyDataTable.tradeDate,
                    StockDailyDataTable.open,
                    StockDailyDataTable.high,
                    StockDailyDataTable.low,
                    StockDailyDataTable.close,
                    StockDailyDataTable.openQfq,
                    StockDailyDataTable.highQfq,
                    StockDailyDataTable.lowQfq,
                    StockDailyDataTable.closeQfq,
                    StockDailyDataTable.volume,
                    StockDailyDataTable.volumeQfq,
                    StockDailyDataTable.turnoverReal,
                )
                .where {
                    val range = (StockDailyDataTable.tradeDate greaterEq startDate) and
                        (StockDailyDataTable.tradeDate lessEq endDate)
                    if (cursor == null) range else range and cursor
                }
                .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    val oq = row[StockDailyDataTable.openQfq].toDouble()
                    val hq = row[StockDailyDataTable.highQfq].toDouble()
                    val lq = row[StockDailyDataTable.lowQfq].toDouble()
                    val cq = row[StockDailyDataTable.closeQfq].toDouble()
                    val vq = row[StockDailyDataTable.volumeQfq].toDouble()
                    DailyOhlcvProjection(
                        tsCode = row[StockDailyDataTable.tsCode],
                        tradeDate = row[StockDailyDataTable.tradeDate],
                        openQfq = if (oq > 0.0) oq else row[StockDailyDataTable.open].toDouble(),
                        highQfq = if (hq > 0.0) hq else row[StockDailyDataTable.high].toDouble(),
                        lowQfq = if (lq > 0.0) lq else row[StockDailyDataTable.low].toDouble(),
                        closeQfq = if (cq > 0.0) cq else row[StockDailyDataTable.close].toDouble(),
                        volumeQfq = if (vq > 0.0) vq else row[StockDailyDataTable.volume].toDouble(),
                        turnoverReal = row[StockDailyDataTable.turnoverReal].toDouble(),
                    )
                }
        }
    }

    fun findRecentOhlcvWindowsForProduction(
        tsCodes: List<String>,
        endDateInclusive: kotlinx.datetime.LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>> {
        if (tsCodes.isEmpty() || limitPerStock <= 0) return emptyMap()

        return stockDb.transaction(StockDailyDataTable, log = false) {
            val result = linkedMapOf<String, MutableList<ProductionOhlcvWindowRow>>()
            tsCodes.distinct().chunked(500).forEach { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                val sql = """
                    SELECT *
                    FROM (
                        SELECT d.ts_code, d.trade_date,
                               d.open_qfq, d.high_qfq, d.low_qfq, d.close_qfq, d.volume_qfq,
                               d.turnover_real,
                               ROW_NUMBER() OVER (
                                   PARTITION BY d.ts_code
                                   ORDER BY d.trade_date DESC
                               ) AS rn
                        FROM stock_daily_data d
                        WHERE d.ts_code IN ($placeholders)
                          AND d.trade_date <= ?
                    ) ranked
                    WHERE ranked.rn <= ?
                    ORDER BY ranked.ts_code ASC, ranked.trade_date ASC
                """.trimIndent()
                val args = chunk.map { VarCharColumnType() to it } +
                    listOf(VarCharColumnType() to endDateInclusive.toString(), IntegerColumnType() to limitPerStock)
                exec(sql, args = args, explicitStatementType = StatementType.SELECT) { rs ->
                    while (rs.next()) {
                        val openQfq = rs.getDouble("open_qfq")
                        val highQfq = rs.getDouble("high_qfq")
                        val lowQfq = rs.getDouble("low_qfq")
                        val closeQfq = rs.getDouble("close_qfq")
                        val volumeQfq = rs.getDouble("volume_qfq")
                        val row = ProductionOhlcvWindowRow(
                            tsCode = rs.getString("ts_code"),
                            tradeDate = kotlinx.datetime.LocalDate.parse(rs.getDate("trade_date").toString()),
                            openQfq = openQfq,
                            highQfq = highQfq,
                            lowQfq = lowQfq,
                            closeQfq = closeQfq,
                            volumeQfq = volumeQfq,
                            turnoverReal = rs.getDouble("turnover_real"),
                            adjustedComplete = openQfq > 0.0 && highQfq > 0.0 && lowQfq > 0.0 &&
                                closeQfq > 0.0 && volumeQfq > 0.0,
                        )
                        result.getOrPut(row.tsCode) { mutableListOf() }.add(row)
                    }
                    true
                }
            }
            result
        }
    }

    fun findProductionOhlcvRange(
        tsCodes: List<String>,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate,
    ): List<ProductionOhlcvWindowRow> {
        if (tsCodes.isEmpty()) return emptyList()

        return stockDb.transaction(StockDailyDataTable, log = false) {
            val result = ArrayList<ProductionOhlcvWindowRow>()
            tsCodes.distinct().chunked(500).forEach { chunk ->
                StockDailyDataTable
                    .select(
                        StockDailyDataTable.tsCode,
                        StockDailyDataTable.tradeDate,
                        StockDailyDataTable.openQfq,
                        StockDailyDataTable.highQfq,
                        StockDailyDataTable.lowQfq,
                        StockDailyDataTable.closeQfq,
                        StockDailyDataTable.volumeQfq,
                        StockDailyDataTable.turnoverReal,
                    )
                    .where {
                        (StockDailyDataTable.tsCode inList chunk) and
                            (StockDailyDataTable.tradeDate greaterEq startDate) and
                            (StockDailyDataTable.tradeDate lessEq endDate)
                    }
                    .orderBy(StockDailyDataTable.tsCode, SortOrder.ASC)
                    .orderBy(StockDailyDataTable.tradeDate, SortOrder.ASC)
                    .forEach { row ->
                        val openQfq = row[StockDailyDataTable.openQfq].toDouble()
                        val highQfq = row[StockDailyDataTable.highQfq].toDouble()
                        val lowQfq = row[StockDailyDataTable.lowQfq].toDouble()
                        val closeQfq = row[StockDailyDataTable.closeQfq].toDouble()
                        val volumeQfq = row[StockDailyDataTable.volumeQfq].toDouble()
                        result += ProductionOhlcvWindowRow(
                            tsCode = row[StockDailyDataTable.tsCode],
                            tradeDate = row[StockDailyDataTable.tradeDate],
                            openQfq = openQfq,
                            highQfq = highQfq,
                            lowQfq = lowQfq,
                            closeQfq = closeQfq,
                            volumeQfq = volumeQfq,
                            turnoverReal = row[StockDailyDataTable.turnoverReal].toDouble(),
                            adjustedComplete = openQfq > 0.0 && highQfq > 0.0 && lowQfq > 0.0 &&
                                closeQfq > 0.0 && volumeQfq > 0.0,
                        )
                    }
            }
            result
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

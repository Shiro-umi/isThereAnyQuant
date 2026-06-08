package org.shiroumi.database.stock

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.StockMinute15mTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object StockMinute15mRepository {

    data class Minute15mRow(
        val tsCode: String,
        val tradeDate: LocalDate,
        val tradeTime: String,
        val open: Float,
        val high: Float,
        val low: Float,
        val close: Float,
        val vol: Float,
        val amount: Float,
        val updatedAtMillis: Long = System.currentTimeMillis(),
    )

    data class DailyStructureRow(
        val tsCode: String,
        val tradeDate: LocalDate,
        val bars: Int,
        val totalVol: Double,
        val totalAmount: Double,
        val firstOpen: Double,
        val firstHigh: Double,
        val firstLow: Double,
        val firstClose: Double,
        val firstVol: Double,
        val firstAmount: Double,
        val lastOpen: Double,
        val lastHigh: Double,
        val lastLow: Double,
        val lastClose: Double,
        val lastVol: Double,
        val lastAmount: Double,
    )

    private const val UPSERT_BATCH_SIZE = 10_000

    fun upsertRows(rows: List<Minute15mRow>) {
        if (rows.isEmpty()) return
        rows.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            stockDb.transaction(StockMinute15mTable, log = false) {
                StockMinute15mTable.batchUpsert(batch) { r ->
                    this[StockMinute15mTable.tsCode] = r.tsCode
                    this[StockMinute15mTable.tradeDate] = r.tradeDate
                    this[StockMinute15mTable.tradeTime] = r.tradeTime
                    this[StockMinute15mTable.open] = r.open
                    this[StockMinute15mTable.high] = r.high
                    this[StockMinute15mTable.low] = r.low
                    this[StockMinute15mTable.close] = r.close
                    this[StockMinute15mTable.vol] = r.vol
                    this[StockMinute15mTable.amount] = r.amount
                    this[StockMinute15mTable.updatedAtMillis] = r.updatedAtMillis
                }
            }
        }
    }

    fun existsForCodeWindow(tsCode: String, fromInclusive: LocalDate, toInclusive: LocalDate): Boolean =
        stockDb.transaction(StockMinute15mTable, log = false) {
            StockMinute15mTable.selectAll()
                .where {
                    (StockMinute15mTable.tsCode eq tsCode) and
                        (StockMinute15mTable.tradeDate greaterEq fromInclusive) and
                        (StockMinute15mTable.tradeDate lessEq toInclusive)
                }
                .limit(1)
                .any()
        }

    fun findRange(fromInclusive: LocalDate, toInclusive: LocalDate): List<Minute15mRow> =
        stockDb.transaction(StockMinute15mTable, log = false) {
            StockMinute15mTable.selectAll()
                .where {
                    (StockMinute15mTable.tradeDate greaterEq fromInclusive) and
                        (StockMinute15mTable.tradeDate lessEq toInclusive)
                }
                .orderBy(StockMinute15mTable.tradeDate to org.jetbrains.exposed.v1.core.SortOrder.ASC)
                .map {
                    Minute15mRow(
                        tsCode = it[StockMinute15mTable.tsCode],
                        tradeDate = it[StockMinute15mTable.tradeDate],
                        tradeTime = it[StockMinute15mTable.tradeTime],
                        open = it[StockMinute15mTable.open],
                        high = it[StockMinute15mTable.high],
                        low = it[StockMinute15mTable.low],
                        close = it[StockMinute15mTable.close],
                        vol = it[StockMinute15mTable.vol],
                        amount = it[StockMinute15mTable.amount],
                        updatedAtMillis = it[StockMinute15mTable.updatedAtMillis],
                    )
                }
        }

    fun streamDailyStructurePage(
        startDate: LocalDate,
        endDate: LocalDate,
        afterTsCode: String?,
        afterTradeDate: LocalDate?,
        limit: Int,
    ): List<DailyStructureRow> =
        stockDb.transaction(StockMinute15mTable, log = false) {
            val cursorSql = if (afterTsCode != null && afterTradeDate != null) {
                "AND (m.ts_code > ? OR (m.ts_code = ? AND m.trade_date > ?))"
            } else {
                ""
            }
            val sql = """
                SELECT
                    agg.ts_code,
                    agg.trade_date,
                    agg.bars,
                    agg.total_vol,
                    agg.total_amount,
                    f.open AS first_open,
                    f.high AS first_high,
                    f.low AS first_low,
                    f.close AS first_close,
                    f.vol AS first_vol,
                    f.amount AS first_amount,
                    l.open AS last_open,
                    l.high AS last_high,
                    l.low AS last_low,
                    l.close AS last_close,
                    l.vol AS last_vol,
                    l.amount AS last_amount
                FROM (
                    SELECT
                        m.ts_code,
                        m.trade_date,
                        MIN(m.trade_time) AS first_time,
                        MAX(m.trade_time) AS last_time,
                        COUNT(*) AS bars,
                        SUM(m.vol) AS total_vol,
                        SUM(m.amount) AS total_amount
                    FROM stock_minute_15m m
                    WHERE m.trade_date >= ? AND m.trade_date <= ?
                    $cursorSql
                    GROUP BY m.ts_code, m.trade_date
                    ORDER BY m.ts_code ASC, m.trade_date ASC
                    LIMIT ?
                ) agg
                JOIN stock_minute_15m f
                  ON f.ts_code = agg.ts_code AND f.trade_time = agg.first_time
                JOIN stock_minute_15m l
                  ON l.ts_code = agg.ts_code AND l.trade_time = agg.last_time
                ORDER BY agg.ts_code ASC, agg.trade_date ASC
            """.trimIndent()
            val args = mutableListOf<Pair<ColumnType<*>, Any?>>()
            args += VarCharColumnType() to startDate.toString()
            args += VarCharColumnType() to endDate.toString()
            if (afterTsCode != null && afterTradeDate != null) {
                args += VarCharColumnType() to afterTsCode
                args += VarCharColumnType() to afterTsCode
                args += VarCharColumnType() to afterTradeDate.toString()
            }
            args += IntegerColumnType() to limit
            val rows = mutableListOf<DailyStructureRow>()
            exec(sql, args = args, explicitStatementType = StatementType.SELECT) { rs ->
                while (rs.next()) {
                    rows += DailyStructureRow(
                        tsCode = rs.getString("ts_code"),
                        tradeDate = LocalDate.parse(rs.getString("trade_date")),
                        bars = rs.getInt("bars"),
                        totalVol = rs.getDouble("total_vol"),
                        totalAmount = rs.getDouble("total_amount"),
                        firstOpen = rs.getDouble("first_open"),
                        firstHigh = rs.getDouble("first_high"),
                        firstLow = rs.getDouble("first_low"),
                        firstClose = rs.getDouble("first_close"),
                        firstVol = rs.getDouble("first_vol"),
                        firstAmount = rs.getDouble("first_amount"),
                        lastOpen = rs.getDouble("last_open"),
                        lastHigh = rs.getDouble("last_high"),
                        lastLow = rs.getDouble("last_low"),
                        lastClose = rs.getDouble("last_close"),
                        lastVol = rs.getDouble("last_vol"),
                        lastAmount = rs.getDouble("last_amount"),
                    )
                }
                true
            }
            rows
        }
}

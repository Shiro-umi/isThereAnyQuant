package org.shiroumi.database.stock

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.StockOpen5mTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/**
 * 每日首根 5min K 线的持久化访问边界（仿 [StockDailyCandleRepository]，object 单例阻塞式）。
 *
 * 职责：① 采集层按交易日/股票批量幂等写入首根 bar；② 盘后追平判断「哪些交易日已采集」；
 * ③ 研究层只读区间查询。表 lazy 自动建（首次 transaction(StockOpen5mTable) 时 createMissingTablesAndColumns）。
 */
object StockOpen5mRepository {

    /** 一条首根 5min 记录（采集/查询的领域行）。 */
    data class Open5mRow(
        val tsCode: String,
        val tradeDate: LocalDate,
        val tradeTime: String,
        val open: Float,
        val high: Float,
        val low: Float,
        val close: Float,
        val vol: Float,
        val amount: Float,
    )

    private const val UPSERT_BATCH_SIZE = 10_000

    /** 批量幂等写入（INSERT ... ON DUPLICATE KEY UPDATE，基于 uk_open5m_code_date）。 */
    fun upsertRows(rows: List<Open5mRow>) {
        if (rows.isEmpty()) return
        rows.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            stockDb.transaction(StockOpen5mTable, log = false) {
                StockOpen5mTable.batchUpsert(batch) { r ->
                    this[StockOpen5mTable.tsCode] = r.tsCode
                    this[StockOpen5mTable.tradeDate] = r.tradeDate
                    this[StockOpen5mTable.tradeTime] = r.tradeTime
                    this[StockOpen5mTable.open] = r.open
                    this[StockOpen5mTable.high] = r.high
                    this[StockOpen5mTable.low] = r.low
                    this[StockOpen5mTable.close] = r.close
                    this[StockOpen5mTable.vol] = r.vol
                    this[StockOpen5mTable.amount] = r.amount
                }
            }
        }
    }

    /** 某交易日是否已有任意首根记录（盘后追平判空）。 */
    fun existsForTradeDate(tradeDate: LocalDate): Boolean =
        stockDb.transaction(StockOpen5mTable, log = false) {
            StockOpen5mTable.selectAll()
                .where { StockOpen5mTable.tradeDate eq tradeDate }
                .limit(1).any()
        }

    /** 区间内已采集的交易日集合（盘后追平定位缺口）。 */
    fun findExistingTradeDates(fromInclusive: LocalDate, toInclusive: LocalDate): Set<LocalDate> =
        stockDb.transaction(StockOpen5mTable, log = false) {
            StockOpen5mTable
                .select(StockOpen5mTable.tradeDate)
                .where {
                    (StockOpen5mTable.tradeDate greaterEq fromInclusive) and
                        (StockOpen5mTable.tradeDate lessEq toInclusive)
                }
                .withDistinct()
                .mapTo(linkedSetOf()) { it[StockOpen5mTable.tradeDate] }
        }

    /** 区间查询（研究层读取，升序）。 */
    fun findRange(fromInclusive: LocalDate, toInclusive: LocalDate): List<Open5mRow> =
        stockDb.transaction(StockOpen5mTable, log = false) {
            StockOpen5mTable.selectAll()
                .where {
                    (StockOpen5mTable.tradeDate greaterEq fromInclusive) and
                        (StockOpen5mTable.tradeDate lessEq toInclusive)
                }
                .orderBy(StockOpen5mTable.tradeDate)
                .map {
                    Open5mRow(
                        tsCode = it[StockOpen5mTable.tsCode],
                        tradeDate = it[StockOpen5mTable.tradeDate],
                        tradeTime = it[StockOpen5mTable.tradeTime],
                        open = it[StockOpen5mTable.open],
                        high = it[StockOpen5mTable.high],
                        low = it[StockOpen5mTable.low],
                        close = it[StockOpen5mTable.close],
                        vol = it[StockOpen5mTable.vol],
                        amount = it[StockOpen5mTable.amount],
                    )
                }
        }
}

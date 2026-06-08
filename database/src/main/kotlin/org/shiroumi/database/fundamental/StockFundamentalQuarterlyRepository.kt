package org.shiroumi.database.fundamental

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.fundamental.table.StockFundamentalQuarterlyTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/** 个股季频财务记录（一行 = 一只股一个报告期）。 */
data class StockFundamentalRecord(
    val tsCode: String,
    val endDate: String,
    val annDate: String? = null,
    val roe: Double? = null,
    val roeDt: Double? = null,
    val eps: Double? = null,
    val netprofitYoy: Double? = null,
    val dtNetprofitYoy: Double? = null,
    val trYoy: Double? = null,
    val orYoy: Double? = null,
    val ocfToSales: Double? = null,
    val ocfps: Double? = null,
)

object StockFundamentalQuarterlyRepository {

    fun upsert(records: List<StockFundamentalRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(StockFundamentalQuarterlyTable, log = false) {
            StockFundamentalQuarterlyTable.batchUpsert(records) { r ->
                this[StockFundamentalQuarterlyTable.tsCode] = r.tsCode
                this[StockFundamentalQuarterlyTable.endDate] = r.endDate
                r.annDate?.let { this[StockFundamentalQuarterlyTable.annDate] = it }
                r.roe?.let { this[StockFundamentalQuarterlyTable.roe] = it }
                r.roeDt?.let { this[StockFundamentalQuarterlyTable.roeDt] = it }
                r.eps?.let { this[StockFundamentalQuarterlyTable.eps] = it }
                r.netprofitYoy?.let { this[StockFundamentalQuarterlyTable.netprofitYoy] = it }
                r.dtNetprofitYoy?.let { this[StockFundamentalQuarterlyTable.dtNetprofitYoy] = it }
                r.trYoy?.let { this[StockFundamentalQuarterlyTable.trYoy] = it }
                r.orYoy?.let { this[StockFundamentalQuarterlyTable.orYoy] = it }
                r.ocfToSales?.let { this[StockFundamentalQuarterlyTable.ocfToSales] = it }
                r.ocfps?.let { this[StockFundamentalQuarterlyTable.ocfps] = it }
            }
        }
    }

    /** 全量读出（H3 研究用，按 ann_date 升序便于 as-of 对齐）。 */
    fun findAll(): List<StockFundamentalRecord> =
        stockDb.transaction(StockFundamentalQuarterlyTable, log = false) {
            StockFundamentalQuarterlyTable.selectAll()
                .orderBy(StockFundamentalQuarterlyTable.annDate, SortOrder.ASC)
                .map(::toRecord)
        }

    fun count(): Long =
        stockDb.transaction(StockFundamentalQuarterlyTable, log = false) {
            StockFundamentalQuarterlyTable.selectAll().count()
        }

    private fun toRecord(row: ResultRow) = StockFundamentalRecord(
        tsCode = row[StockFundamentalQuarterlyTable.tsCode],
        endDate = row[StockFundamentalQuarterlyTable.endDate],
        annDate = row[StockFundamentalQuarterlyTable.annDate],
        roe = row[StockFundamentalQuarterlyTable.roe],
        roeDt = row[StockFundamentalQuarterlyTable.roeDt],
        eps = row[StockFundamentalQuarterlyTable.eps],
        netprofitYoy = row[StockFundamentalQuarterlyTable.netprofitYoy],
        dtNetprofitYoy = row[StockFundamentalQuarterlyTable.dtNetprofitYoy],
        trYoy = row[StockFundamentalQuarterlyTable.trYoy],
        orYoy = row[StockFundamentalQuarterlyTable.orYoy],
        ocfToSales = row[StockFundamentalQuarterlyTable.ocfToSales],
        ocfps = row[StockFundamentalQuarterlyTable.ocfps],
    )
}

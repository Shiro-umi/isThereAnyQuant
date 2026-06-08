package org.shiroumi.database.moneyflow

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.moneyflow.table.StockMoneyFlowTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/** 个股日频资金流记录（一行 = 一只股一个交易日）。金额单位：万元。 */
data class StockMoneyFlowRecord(
    val tsCode: String,
    val tradeDate: String,            // YYYYMMDD
    val buyLgAmount: Double? = null,
    val sellLgAmount: Double? = null,
    val buyElgAmount: Double? = null,
    val sellElgAmount: Double? = null,
    val netMfAmount: Double? = null,
)

/** 资金流投影（供研究层读取）。tradeDate 标准化为 YYYY-MM-DD，与日 K 投影口径一致便于对齐。 */
data class MoneyFlowProjection(
    val tsCode: String,
    val tradeDate: String,            // YYYY-MM-DD
    val buyLgAmount: Double?,
    val sellLgAmount: Double?,
    val buyElgAmount: Double?,
    val sellElgAmount: Double?,
    val netMfAmount: Double?,
)

object StockMoneyFlowRepository {

    fun upsert(records: List<StockMoneyFlowRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(StockMoneyFlowTable, log = false) {
            StockMoneyFlowTable.batchUpsert(records) { r ->
                this[StockMoneyFlowTable.tsCode] = r.tsCode
                this[StockMoneyFlowTable.tradeDate] = r.tradeDate
                r.buyLgAmount?.let { this[StockMoneyFlowTable.buyLgAmount] = it }
                r.sellLgAmount?.let { this[StockMoneyFlowTable.sellLgAmount] = it }
                r.buyElgAmount?.let { this[StockMoneyFlowTable.buyElgAmount] = it }
                r.sellElgAmount?.let { this[StockMoneyFlowTable.sellElgAmount] = it }
                r.netMfAmount?.let { this[StockMoneyFlowTable.netMfAmount] = it }
            }
        }
    }

    /**
     * 分页投影（keyset 游标 (ts_code, trade_date)），区间 [start, end]（YYYYMMDD）。
     * 输出 tradeDate 标准化为 YYYY-MM-DD，与 StockDailyCandleRepository 投影对齐。
     */
    fun streamMoneyFlowPage(
        startDate: String,
        endDate: String,
        afterTsCode: String?,
        afterTradeDate: String?,
        limit: Int,
    ): List<MoneyFlowProjection> =
        stockDb.transaction(StockMoneyFlowTable, log = false) {
            val cursor = if (afterTsCode != null && afterTradeDate != null) {
                (StockMoneyFlowTable.tsCode greater afterTsCode) or
                    ((StockMoneyFlowTable.tsCode eq afterTsCode) and (StockMoneyFlowTable.tradeDate greater afterTradeDate))
            } else {
                null
            }
            StockMoneyFlowTable
                .selectAll()
                .where {
                    val range = (StockMoneyFlowTable.tradeDate greaterEq startDate) and
                        (StockMoneyFlowTable.tradeDate lessEq endDate)
                    if (cursor == null) range else range and cursor
                }
                .orderBy(StockMoneyFlowTable.tsCode, SortOrder.ASC)
                .orderBy(StockMoneyFlowTable.tradeDate, SortOrder.ASC)
                .limit(limit)
                .map(::toProjection)
        }

    fun count(): Long =
        stockDb.transaction(StockMoneyFlowTable, log = false) {
            StockMoneyFlowTable.selectAll().count()
        }

    private fun toProjection(row: ResultRow): MoneyFlowProjection {
        val raw = row[StockMoneyFlowTable.tradeDate]
        val iso = if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}" else raw
        return MoneyFlowProjection(
            tsCode = row[StockMoneyFlowTable.tsCode],
            tradeDate = iso,
            buyLgAmount = row[StockMoneyFlowTable.buyLgAmount],
            sellLgAmount = row[StockMoneyFlowTable.sellLgAmount],
            buyElgAmount = row[StockMoneyFlowTable.buyElgAmount],
            sellElgAmount = row[StockMoneyFlowTable.sellElgAmount],
            netMfAmount = row[StockMoneyFlowTable.netMfAmount],
        )
    }
}

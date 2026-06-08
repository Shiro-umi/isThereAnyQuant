package org.shiroumi.database.stock

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.TopListTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/** 龙虎榜每日汇总记录（一行 = 一只上榜股一个交易日）。trade_date 为 YYYYMMDD。 */
data class TopListRecord(
    val tsCode: String,
    val tradeDate: String,            // YYYYMMDD
    val name: String? = null,
    val close: Double? = null,
    val pctChange: Double? = null,
    val turnoverRate: Double? = null,
    val amount: Double? = null,
    val lBuy: Double? = null,
    val lSell: Double? = null,
    val lAmount: Double? = null,
    val netAmount: Double? = null,
    val netRate: Double? = null,
    val amountRate: Double? = null,
    val floatValues: Double? = null,
    val reason: String? = null,
)

/** 龙虎榜汇总投影（tradeDate 标准化 YYYY-MM-DD，与日 K 投影对齐，供 research API）。 */
data class TopListProjection(
    val tsCode: String,
    val tradeDate: String,            // YYYY-MM-DD
    val netAmount: Double?,
    val netRate: Double?,
    val amount: Double?,
    val reason: String?,
)

object TopListRepository {

    fun upsert(records: List<TopListRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(TopListTable, log = false) {
            TopListTable.batchUpsert(records) { r ->
                this[TopListTable.tsCode] = r.tsCode
                this[TopListTable.tradeDate] = r.tradeDate
                r.name?.let { this[TopListTable.name] = it }
                r.close?.let { this[TopListTable.close] = it }
                r.pctChange?.let { this[TopListTable.pctChange] = it }
                r.turnoverRate?.let { this[TopListTable.turnoverRate] = it }
                r.amount?.let { this[TopListTable.amount] = it }
                r.lBuy?.let { this[TopListTable.lBuy] = it }
                r.lSell?.let { this[TopListTable.lSell] = it }
                r.lAmount?.let { this[TopListTable.lAmount] = it }
                r.netAmount?.let { this[TopListTable.netAmount] = it }
                r.netRate?.let { this[TopListTable.netRate] = it }
                r.amountRate?.let { this[TopListTable.amountRate] = it }
                r.floatValues?.let { this[TopListTable.floatValues] = it }
                r.reason?.let { this[TopListTable.reason] = it }
            }
        }
    }

    /** 区间全量投影（龙虎榜稀疏，每日数十~千条，无需分页）。tradeDate 还原 ISO。 */
    fun findRange(startDate: String, endDate: String): List<TopListProjection> =
        stockDb.transaction(TopListTable, log = false) {
            TopListTable.selectAll()
                .where { (TopListTable.tradeDate greaterEq startDate) and (TopListTable.tradeDate lessEq endDate) }
                .orderBy(TopListTable.tradeDate, SortOrder.ASC)
                .orderBy(TopListTable.tsCode, SortOrder.ASC)
                .map(::toProjection)
        }

    fun count(): Long =
        stockDb.transaction(TopListTable, log = false) {
            TopListTable.selectAll().count()
        }

    fun findCodesByTradeDate(tradeDate: LocalDate): List<String> =
        stockDb.transaction(TopListTable, log = false) {
            TopListTable.selectAll()
                .where { TopListTable.tradeDate eq tradeDate.toCompactString() }
                .orderBy(TopListTable.tsCode, SortOrder.ASC)
                .map { it[TopListTable.tsCode] }
                .distinct()
        }

    private fun toProjection(row: ResultRow): TopListProjection {
        val raw = row[TopListTable.tradeDate]
        val iso = if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}" else raw
        return TopListProjection(
            tsCode = row[TopListTable.tsCode],
            tradeDate = iso,
            netAmount = row[TopListTable.netAmount],
            netRate = row[TopListTable.netRate],
            amount = row[TopListTable.amount],
            reason = row[TopListTable.reason],
        )
    }

    private fun LocalDate.toCompactString(): String =
        toString().replace("-", "")
}

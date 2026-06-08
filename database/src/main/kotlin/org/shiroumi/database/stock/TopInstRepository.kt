package org.shiroumi.database.stock

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.TopInstTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/** 龙虎榜营业部明细记录（一行 = 一只上榜股一日一营业部一方向）。trade_date 为 YYYYMMDD。 */
data class TopInstRecord(
    val tsCode: String,
    val tradeDate: String,            // YYYYMMDD
    val exalter: String,              // 营业部名称（原文）
    val side: String,                 // 0=买入前5 / 1=卖出前5
    val buy: Double? = null,
    val buyRate: Double? = null,
    val sell: Double? = null,
    val sellRate: Double? = null,
    val netBuy: Double? = null,
    val reason: String? = null,
)

/** 营业部明细投影（tradeDate 标准化 YYYY-MM-DD；exalter 原文透传，散户/机构分类在 pytorch 装配层做）。 */
data class TopInstProjection(
    val tsCode: String,
    val tradeDate: String,            // YYYY-MM-DD
    val exalter: String,
    val side: String,
    val buy: Double?,
    val sell: Double?,
    val netBuy: Double?,
)

object TopInstRepository {

    fun upsert(records: List<TopInstRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(TopInstTable, log = false) {
            TopInstTable.batchUpsert(records) { r ->
                this[TopInstTable.tsCode] = r.tsCode
                this[TopInstTable.tradeDate] = r.tradeDate
                this[TopInstTable.exalter] = r.exalter
                this[TopInstTable.side] = r.side
                r.buy?.let { this[TopInstTable.buy] = it }
                r.buyRate?.let { this[TopInstTable.buyRate] = it }
                r.sell?.let { this[TopInstTable.sell] = it }
                r.sellRate?.let { this[TopInstTable.sellRate] = it }
                r.netBuy?.let { this[TopInstTable.netBuy] = it }
                r.reason?.let { this[TopInstTable.reason] = it }
            }
        }
    }

    /** 区间全量投影（营业部明细每日数千~万条，区间装配一次性拉取）。tradeDate 还原 ISO。 */
    fun findRange(startDate: String, endDate: String): List<TopInstProjection> =
        stockDb.transaction(TopInstTable, log = false) {
            TopInstTable.selectAll()
                .where { (TopInstTable.tradeDate greaterEq startDate) and (TopInstTable.tradeDate lessEq endDate) }
                .orderBy(TopInstTable.tradeDate, SortOrder.ASC)
                .orderBy(TopInstTable.tsCode, SortOrder.ASC)
                .map(::toProjection)
        }

    fun count(): Long =
        stockDb.transaction(TopInstTable, log = false) {
            TopInstTable.selectAll().count()
        }

    private fun toProjection(row: ResultRow): TopInstProjection {
        val raw = row[TopInstTable.tradeDate]
        val iso = if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}" else raw
        return TopInstProjection(
            tsCode = row[TopInstTable.tsCode],
            tradeDate = iso,
            exalter = row[TopInstTable.exalter],
            side = row[TopInstTable.side],
            buy = row[TopInstTable.buy],
            sell = row[TopInstTable.sell],
            netBuy = row[TopInstTable.netBuy],
        )
    }
}

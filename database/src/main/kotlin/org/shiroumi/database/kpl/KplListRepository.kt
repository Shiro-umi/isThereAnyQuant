package org.shiroumi.database.kpl

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.kpl.table.KplListTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

/** 开盘啦榜单记录（一行 = 一只涨停股一个交易日）。 */
data class KplListRecord(
    val tsCode: String,
    val tradeDate: String,            // YYYYMMDD
    val name: String? = null,
    val theme: String? = null,
    val status: String? = null,
    val tag: String? = null,
    val luDesc: String? = null,
    val turnoverRate: Double? = null,
)

/** 题材投影（tradeDate 标准化 YYYY-MM-DD，与日 K 投影对齐）。 */
data class KplListProjection(
    val tsCode: String,
    val tradeDate: String,            // YYYY-MM-DD
    val theme: String?,
    val status: String?,
    val tag: String?,
)

object KplListRepository {

    fun upsert(records: List<KplListRecord>) {
        if (records.isEmpty()) return
        stockDb.transaction(KplListTable, log = false) {
            KplListTable.batchUpsert(records) { r ->
                this[KplListTable.tsCode] = r.tsCode
                this[KplListTable.tradeDate] = r.tradeDate
                r.name?.let { this[KplListTable.name] = it }
                r.theme?.let { this[KplListTable.theme] = it }
                r.status?.let { this[KplListTable.status] = it }
                r.tag?.let { this[KplListTable.tag] = it }
                r.luDesc?.let { this[KplListTable.luDesc] = it }
                r.turnoverRate?.let { this[KplListTable.turnoverRate] = it }
            }
        }
    }

    /** 区间全量投影（题材事件稀疏，每日数十~百条，无需分页）。tradeDate 还原 ISO。 */
    fun findRange(startDate: String, endDate: String): List<KplListProjection> =
        stockDb.transaction(KplListTable, log = false) {
            KplListTable.selectAll()
                .where { (KplListTable.tradeDate greaterEq startDate) and (KplListTable.tradeDate lessEq endDate) }
                .orderBy(KplListTable.tradeDate, SortOrder.ASC)
                .map(::toProjection)
        }

    fun count(): Long =
        stockDb.transaction(KplListTable, log = false) {
            KplListTable.selectAll().count()
        }

    private fun toProjection(row: ResultRow): KplListProjection {
        val raw = row[KplListTable.tradeDate]
        val iso = if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}" else raw
        return KplListProjection(
            tsCode = row[KplListTable.tsCode],
            tradeDate = iso,
            theme = row[KplListTable.theme],
            status = row[KplListTable.status],
            tag = row[KplListTable.tag],
        )
    }
}

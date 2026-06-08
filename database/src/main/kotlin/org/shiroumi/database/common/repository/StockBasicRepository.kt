package org.shiroumi.database.common.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object StockBasicRepository {
    fun getAllSymbols(): List<String> = stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.selectAll()
            .map { it[StockBasicTable.tsCode] }
    }

    fun getActiveSymbols(): List<String> = stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.selectAll()
            .where { StockBasicTable.listStatus eq "L" }
            .map { it[StockBasicTable.tsCode] }
    }

    /** 标的静态画像（ts_code -> 名称 + 上市日），供研究层划「可投资域」（ST 看名称、次新看上市日）。 */
    data class BasicProfile(val tsCode: String, val name: String, val listDate: LocalDate?)
    data class BacktestBasicProfile(
        val tsCode: String,
        val name: String,
        val listStatus: String,
        val listDate: LocalDate?,
    )

    fun findProfiles(): List<BasicProfile> = stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.selectAll().map { row ->
            BasicProfile(
                tsCode = row[StockBasicTable.tsCode],
                name = row[StockBasicTable.name],
                listDate = parseBasicDate(row[StockBasicTable.listDate]),
            )
        }
    }

    fun findActiveProfiles(): List<BasicProfile> = stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.selectAll()
            .where { StockBasicTable.listStatus eq "L" }
            .map { row ->
                BasicProfile(
                    tsCode = row[StockBasicTable.tsCode],
                    name = row[StockBasicTable.name],
                    listDate = parseBasicDate(row[StockBasicTable.listDate]),
                )
            }
    }

    fun findBacktestProfiles(): List<BacktestBasicProfile> = stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.selectAll()
            .map { row ->
                BacktestBasicProfile(
                    tsCode = row[StockBasicTable.tsCode],
                    name = row[StockBasicTable.name],
                    listStatus = row[StockBasicTable.listStatus],
                    listDate = parseBasicDate(row[StockBasicTable.listDate]),
                )
            }
    }

    fun findBacktestStatuses(
        tradeDate: LocalDate,
        tsCodes: Collection<String>,
        excludeIpoDays: Int,
        excludeSt: Boolean,
    ): BacktestStockStatuses {
        val rows = stockDb.transaction(StockBasicTable, log = false) {
            val query = StockBasicTable.selectAll()
            val filtered = if (tsCodes.isEmpty()) query else query.where { StockBasicTable.tsCode inList tsCodes.distinct() }
            filtered.map {
                StockBasicStatusRow(
                    tsCode = it[StockBasicTable.tsCode],
                    name = it[StockBasicTable.name],
                    listStatus = it[StockBasicTable.listStatus],
                    listDate = it[StockBasicTable.listDate],
                )
            }
        }
        return statusesFromRows(tradeDate, rows, excludeIpoDays, excludeSt)
    }

    fun findAllBacktestStatuses(
        tradeDate: LocalDate,
        excludeIpoDays: Int,
        excludeSt: Boolean,
    ): BacktestStockStatuses {
        val rows = stockDb.transaction(StockBasicTable, log = false) {
            StockBasicTable.selectAll()
                .map {
                    StockBasicStatusRow(
                        tsCode = it[StockBasicTable.tsCode],
                        name = it[StockBasicTable.name],
                        listStatus = it[StockBasicTable.listStatus],
                        listDate = it[StockBasicTable.listDate],
                    )
                }
        }
        return statusesFromRows(tradeDate, rows, excludeIpoDays, excludeSt)
    }

    private fun statusesFromRows(
        tradeDate: LocalDate,
        rows: List<StockBasicStatusRow>,
        excludeIpoDays: Int,
        excludeSt: Boolean,
    ): BacktestStockStatuses {
        val openDatesByListDate = mutableMapOf<LocalDate, Int>()
        val suspended = linkedSetOf<String>()
        val ipoFrozen = linkedSetOf<String>()
        val delisted = linkedSetOf<String>()
        for (row in rows) {
            when (row.listStatus.uppercase()) {
                "D" -> delisted += row.tsCode
                "P" -> suspended += row.tsCode
            }
            if (excludeSt && row.name.contains("ST", ignoreCase = true)) {
                suspended += row.tsCode
            }
            val listDate = parseBasicDate(row.listDate) ?: continue
            if (excludeIpoDays > 0 && tradeDate >= listDate) {
                // 性能优化：如果自然日间隔大于 20 天，交易天数必定已经超过 excludeIpoDays（默认 5 ），直接跳过 SQL 查询
                if (listDate.daysUntil(tradeDate) <= 20) {
                    val openDays = openDatesByListDate.getOrPut(listDate) {
                        TradingCalendarRepository.findOpenDates(listDate, tradeDate).size
                    }
                    if (openDays in 1..excludeIpoDays) ipoFrozen += row.tsCode
                }
            }
        }
        return BacktestStockStatuses(
            suspended = suspended,
            ipoFrozen = ipoFrozen,
            delisted = delisted,
        )
    }

    private fun parseBasicDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank() || raw.length != 8) return null
        return runCatching {
            LocalDate.parse("${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}")
        }.getOrNull()
    }
}

data class BacktestStockStatuses(
    val suspended: Set<String> = emptySet(),
    val ipoFrozen: Set<String> = emptySet(),
    val delisted: Set<String> = emptySet(),
)

private data class StockBasicStatusRow(
    val tsCode: String,
    val name: String,
    val listStatus: String,
    val listDate: String?,
)

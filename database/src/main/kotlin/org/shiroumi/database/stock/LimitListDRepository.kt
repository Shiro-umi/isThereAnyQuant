package org.shiroumi.database.stock

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stock.table.LimitListDTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object LimitListDRepository {

    fun replaceForTradeDate(tradeDate: LocalDate, records: List<LimitListDRecord>) {
        val now = System.currentTimeMillis()
        stockDb.transaction(LimitListDTable, log = false) {
            LimitListDTable.deleteWhere { LimitListDTable.tradeDate eq tradeDate }
            if (records.isNotEmpty()) {
                LimitListDTable.batchUpsert(records) { record ->
                    this[LimitListDTable.tradeDate] = record.tradeDate
                    this[LimitListDTable.tsCode] = record.tsCode
                    this[LimitListDTable.industry] = record.industry
                    this[LimitListDTable.name] = record.name
                    this[LimitListDTable.close] = record.close
                    this[LimitListDTable.pctChg] = record.pctChg
                    this[LimitListDTable.amount] = record.amount
                    this[LimitListDTable.limitAmount] = record.limitAmount
                    this[LimitListDTable.floatMv] = record.floatMv
                    this[LimitListDTable.totalMv] = record.totalMv
                    this[LimitListDTable.turnoverRatio] = record.turnoverRatio
                    this[LimitListDTable.fdAmount] = record.fdAmount
                    this[LimitListDTable.firstTime] = record.firstTime
                    this[LimitListDTable.lastTime] = record.lastTime
                    this[LimitListDTable.openTimes] = record.openTimes
                    this[LimitListDTable.upStat] = record.upStat
                    this[LimitListDTable.limitTimes] = record.limitTimes
                    this[LimitListDTable.limitType] = record.limitType
                    this[LimitListDTable.updatedAtMillis] = now
                }
            }
        }
    }

    fun findByTradeDate(tradeDate: LocalDate): List<LimitListDRecord> {
        return stockDb.transaction(LimitListDTable, log = false) {
            LimitListDTable.selectAll()
                .where { LimitListDTable.tradeDate eq tradeDate }
                .orderBy(LimitListDTable.limitType, SortOrder.ASC)
                .orderBy(LimitListDTable.tsCode, SortOrder.ASC)
                .map { it.toDomain() }
        }
    }

    fun findRange(
        startDate: LocalDate,
        endDate: LocalDate,
        tsCode: String? = null,
        limitType: String? = null,
    ): List<LimitListDRecord> {
        return stockDb.transaction(LimitListDTable, log = false) {
            LimitListDTable.selectAll()
                .where {
                    listOfNotNull(
                        LimitListDTable.tradeDate greaterEq startDate,
                        LimitListDTable.tradeDate lessEq endDate,
                        tsCode?.let { LimitListDTable.tsCode eq it },
                        limitType?.let { LimitListDTable.limitType eq it },
                    ).reduce { acc, op -> acc and op }
                }
                .orderBy(LimitListDTable.tradeDate, SortOrder.DESC)
                .orderBy(LimitListDTable.tsCode, SortOrder.ASC)
                .map { it.toDomain() }
        }
    }

    private fun ResultRow.toDomain(): LimitListDRecord {
        val table = LimitListDTable
        return LimitListDRecord(
            tradeDate = this[table.tradeDate],
            tsCode = this[table.tsCode],
            industry = this[table.industry],
            name = this[table.name],
            close = this[table.close],
            pctChg = this[table.pctChg],
            amount = this[table.amount],
            limitAmount = this[table.limitAmount],
            floatMv = this[table.floatMv],
            totalMv = this[table.totalMv],
            turnoverRatio = this[table.turnoverRatio],
            fdAmount = this[table.fdAmount],
            firstTime = this[table.firstTime],
            lastTime = this[table.lastTime],
            openTimes = this[table.openTimes],
            upStat = this[table.upStat],
            limitTimes = this[table.limitTimes],
            limitType = this[table.limitType],
        )
    }
}

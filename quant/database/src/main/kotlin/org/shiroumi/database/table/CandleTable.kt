package org.shiroumi.database.table

import org.ktorm.dsl.AssignmentsBuilder
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.double
import org.ktorm.schema.varchar
import org.shiroumi.database.stockDb
import org.shiroumi.ksp.KtormAssignments
import org.shiroumi.ksp.KtormAssignmentsInclude
import org.shiroumi.model.database.Candle

// candle table def
@KtormAssignments
abstract class CandleTable(tableName: String) : Table<Candle>(tableName) {
    @KtormAssignmentsInclude
    val date = varchar("date").bindTo { c -> c.date }
    @KtormAssignmentsInclude
    val open = double("open").bindTo { c -> c.open }
    @KtormAssignmentsInclude
    val close = double("close").bindTo { c -> c.close }
    @KtormAssignmentsInclude
    val high = double("high").bindTo { c -> c.high }
    @KtormAssignmentsInclude
    val low = double("low").bindTo { c -> c.low }
    @KtormAssignmentsInclude
    val volume = double("volume").bindTo { c -> c.volume }
    @KtormAssignmentsInclude
    val turnover = double("turnover").bindTo { c -> c.turnover }
    @KtormAssignmentsInclude
    val amplitude = double("amplitude").bindTo { c -> c.amplitude }
    @KtormAssignmentsInclude
    val changePercent = double("change_percent").bindTo { c -> c.changePercent }
    @KtormAssignmentsInclude
    val changeAmount = double("change_amount").bindTo { c -> c.changeAmount }
    @KtormAssignmentsInclude
    val turnoverRate = double("turnover_rate").bindTo { c -> c.turnoverRate }

    fun AssignmentsBuilder.set(table: CandleTable, new: Candle) {
        set(table.open, new.open)
        set(table.close, new.close)
        set(table.high, new.high)
        set(table.low, new.low)
        set(table.volume, new.volume)
        set(table.turnover, new.turnover)
        set(table.turnoverRate, new.turnoverRate)
        set(table.amplitude, new.amplitude)
        set(table.changePercent, new.changePercent)
        set(table.changeAmount, new.changeAmount)
    }
}

// candle seq of target table
@Suppress("unused")
val String.candleSeq: EntitySequence<Candle, out CandleTable>
    get() {
        createCandleTableIfNotExists(this)
        return stockDb.sequenceOf(object : CandleTable(this) {})
    }

val String.candleTable: CandleTable
    get() {
        createCandleTableIfNotExists(this)
        return object : CandleTable(this) {}
    }

// new candle table
private fun createCandleTableIfNotExists(tableName: String) {
    val sql = """
        CREATE TABLE IF NOT EXISTS `$tableName` (
            `date` VARCHAR(255) NOT NULL,
            `open` DOUBLE NOT NULL,
            `close` DOUBLE NOT NULL,
            `high` DOUBLE NOT NULL,
            `low` DOUBLE NOT NULL,
            `volume` DOUBLE NOT NULL,
            `turnover` DOUBLE NOT NULL,
            `amplitude` DOUBLE NOT NULL,
            `change_percent` DOUBLE NOT NULL,
            `change_amount` DOUBLE NOT NULL,
            `turnover_rate` DOUBLE NOT NULL);
    """.trimIndent()
    stockDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}
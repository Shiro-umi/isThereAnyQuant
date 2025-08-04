package org.shiroumi.database.table

import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.float
import org.ktorm.schema.varchar
import org.shiroumi.database.stockDb
import org.shiroumi.ksp.KtormAssignments
import org.shiroumi.ksp.KtormAssignmentsInclude
import org.shiroumi.model.database.Candle


// StkFactor 表定义
@KtormAssignments
abstract class CandleTable(tableName: String) : Table<Candle>(tableName) {
    @KtormAssignmentsInclude
    val tsCode = varchar("ts_code").primaryKey().bindTo { it.tsCode }

    @KtormAssignmentsInclude
    val tradeDate = varchar("trade_date").primaryKey().bindTo { it.tradeDate }

    @KtormAssignmentsInclude
    val adjFactor = float("adj_factor").bindTo { it.adjFactor }

    @KtormAssignmentsInclude
    val close = float("close").bindTo { it.close }

    @KtormAssignmentsInclude
    val open = float("open").bindTo { it.open }

    @KtormAssignmentsInclude
    val high = float("high").bindTo { it.high }

    @KtormAssignmentsInclude
    val low = float("low").bindTo { it.low }

    @KtormAssignmentsInclude
    val vol = float("vol").bindTo { it.vol }

    @KtormAssignmentsInclude
    val amount = float("amount").bindTo { it.amount }

    @KtormAssignmentsInclude
    val openHfq = float("open_hfq").bindTo { it.openHfq }

    @KtormAssignmentsInclude
    val openQfq = float("open_qfq").bindTo { it.openQfq }

    @KtormAssignmentsInclude
    val closeHfq = float("close_hfq").bindTo { it.closeHfq }

    @KtormAssignmentsInclude
    val closeQfq = float("close_qfq").bindTo { it.closeQfq }

    @KtormAssignmentsInclude
    val highHfq = float("high_hfq").bindTo { it.highHfq }

    @KtormAssignmentsInclude
    val highQfq = float("high_qfq").bindTo { it.highQfq }

    @KtormAssignmentsInclude
    val lowHfq = float("low_hfq").bindTo { it.lowHfq }

    @KtormAssignmentsInclude
    val lowQfq = float("low_qfq").bindTo { it.lowQfq }
}

@Suppress("unused")
val String.candleSeq: EntitySequence<Candle, CandleTable>
    get() {
        createStkFactorTableIfNotExists(this)
        return stockDb.sequenceOf(object : CandleTable(this) {})
    }

val String.candleTable: CandleTable
    get() {
        createStkFactorTableIfNotExists(this)
        return object : CandleTable(this) {}
    }

// 如果表不存在则创建
@Suppress("SqlNoDataSourceInspection")
private fun createStkFactorTableIfNotExists(tableName: String) {
    val sql = """
 CREATE TABLE IF NOT EXISTS `$tableName` (
	`ts_code` VARCHAR(255) NOT NULL,
	`trade_date` VARCHAR(255) NOT NULL,
    `adj_factor` FLOAT NOT NULL,
	`close` FLOAT NOT NULL,
	`open` FLOAT NOT NULL,
	`high` FLOAT NOT NULL,
	`low` FLOAT NOT NULL,
	`vol` FLOAT NOT NULL,
	`amount` FLOAT NOT NULL,
	`open_hfq` FLOAT NOT NULL,
	`open_qfq` FLOAT NOT NULL,
	`close_hfq` FLOAT NOT NULL,
	`close_qfq` FLOAT NOT NULL,
	`high_hfq` FLOAT NOT NULL,
	`high_qfq` FLOAT NOT NULL,
	`low_hfq` FLOAT NOT NULL,
	`low_qfq` FLOAT NOT NULL,
	PRIMARY KEY (`ts_code`, `trade_date`)
);
    """.trimIndent()
    stockDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}
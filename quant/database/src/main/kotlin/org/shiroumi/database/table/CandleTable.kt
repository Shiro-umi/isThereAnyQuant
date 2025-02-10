package org.shiroumi.database.table

import org.ktorm.entity.EntitySequence
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.double
import org.ktorm.schema.varchar
import org.shiroumi.database.stockDb
import org.shiroumi.model.database.Candle

// candle table def
abstract class CandleTable(tableName: String) : Table<Candle>(tableName) {
    val date = varchar("date").bindTo { c -> c.date }
    val open = double("open").bindTo { c -> c.open }
    val close = double("close").bindTo { c -> c.close }
    val high = double("high").bindTo { c -> c.high }
    val low = double("low").bindTo { c -> c.low }
    val volume = double("volume").bindTo { c -> c.volume }
    val turnover = double("turnover").bindTo { c -> c.turnover }
    val amplitude = double("amplitude").bindTo { c -> c.amplitude }
    val changePercent = double("change_percent").bindTo { c -> c.changePercent }
    val changeAmount = double("change_amount").bindTo { c -> c.changeAmount }
    val turnoverRate = double("turnover_rate").bindTo { c -> c.turnoverRate }
}

// candle seq of target table
val String.candleSeq: EntitySequence<Candle, out CandleTable>
    get() {
        createCandleTableIfNotExists(this)
        return stockDb.sequenceOf(object : CandleTable(this) {})
    }

// new candle table
private fun createCandleTableIfNotExists(tableName: String) {
    val sql = """
        CREATE TABLE IF NOT EXISTS $tableName (
            date VARCHAR(255) NOT NULL,          -- 日期
            open DOUBLE NOT NULL,                -- 开盘价
            close DOUBLE NOT NULL,               -- 收盘价
            high DOUBLE NOT NULL,                -- 最高价
            low DOUBLE NOT NULL,                 -- 最低价
            volume DOUBLE NOT NULL,              -- 成交量
            turnover DOUBLE NOT NULL,            -- 成交额
            amplitude DOUBLE NOT NULL,           -- 振幅
            change_percent DOUBLE NOT NULL,      -- 涨跌幅百分比
            change_amount DOUBLE NOT NULL,       -- 涨跌金额
            turnover_rate DOUBLE NOT NULL        -- 换手率
        );
    """.trimIndent()
    stockDb.useConnection { conn ->
        conn.prepareStatement(sql).execute()
    }
}
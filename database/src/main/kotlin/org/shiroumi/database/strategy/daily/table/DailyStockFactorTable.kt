package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyStockFactorTable : Table(name = "daily_stock_factor") {
    val tradeDate = date("trade_date")
    val tsCode = varchar("ts_code", 15)
    val signalBasis = varchar("signal_basis", 16)
    val executionBasis = varchar("execution_basis", 16)
    val sufficientHistory = bool("sufficient_history").default(false)
    val requiredHistory = integer("required_history")
    val open = double("open")
    val high = double("high")
    val low = double("low")
    val close = double("close")
    val volume = double("volume")
    val executionOpen = double("execution_open")
    val executionClose = double("execution_close")
    val hfqFactor = double("hfq_factor")
    val ema10 = double("ema10").default(0.0)
    val ema30 = double("ema30").default(0.0)
    val emaBull = bool("ema_bull").default(false)
    val atr14 = double("atr14").default(0.0)
    val signal = bool("signal").default(false)
    val momentum20 = double("momentum_20").default(0.0)
    val volRatio520 = double("vol_ratio_5_20").default(0.0)
    val amomCombined = double("amom_combined").default(0.0)
    val rankScore = double("rank_score").default(0.0)

    init {
        uniqueIndex("uk_daily_stock_factor", tradeDate, tsCode)
    }
}
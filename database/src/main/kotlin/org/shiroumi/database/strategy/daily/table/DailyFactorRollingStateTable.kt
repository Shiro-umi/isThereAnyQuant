package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyFactorRollingStateTable : Table(name = "daily_factor_rolling_state") {
    val tradeDate = date("trade_date")
    val tsCode = varchar("ts_code", 15)
    val signalBasis = varchar("signal_basis", 16)
    val executionBasis = varchar("execution_basis", 16)
    val requiredHistory = integer("required_history")
    val barsCount = integer("bars_count")
    val emaShort = double("ema_short")
    val emaLong = double("ema_long")
    val atr = double("atr")
    val holding = bool("holding")
    val stopPrice = double("stop_price")
    val holdingDays = integer("holding_days")
    val shortVolumeSum = double("short_volume_sum")
    val longVolumeSum = double("long_volume_sum")
    val prevClose = double("prev_close")
    val recentReturnsJson = text("recent_returns_json")
    val recentClosesJson = text("recent_closes_json")
    val recentVolumesJson = text("recent_volumes_json")
    val momentumBaseClose = double("momentum_base_close").default(0.0)

    init {
        uniqueIndex("uk_daily_factor_rolling_state", tradeDate, tsCode)
    }
}

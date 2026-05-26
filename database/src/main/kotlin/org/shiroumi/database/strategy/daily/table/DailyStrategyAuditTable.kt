package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyStrategyAuditTable : Table(name = "daily_strategy_audit") {
    val tradeDate = date("trade_date").uniqueIndex()
    val universeSize = integer("universe_size")
    val signalPositiveCount = integer("signal_positive_count")
    val selectedCount = integer("selected_count")
    val emptyReason = varchar("empty_reason", 255).nullable()
    val newlySelectedJson = text("newly_selected_json").nullable()
    val droppedJson = text("dropped_json").nullable()
    val currentPositionsJson = text("current_positions_json").nullable()

    val sentimentExposure = double("sentiment_exposure").default(0.0)
    val bullRatio = double("bull_ratio").default(0.0)
    val marketVol = double("market_vol").default(0.0)
    val fftScore = double("fft_score").default(0.0)
    val residualScore = double("residual_score").default(0.0)
    val accelZ = double("accel_z").default(0.0)
    val volZ = double("vol_z").default(0.0)

    val ratioNorm = double("ratio_norm").default(0.0)
    val volScore = double("vol_score").default(0.0)
    val accelScore = double("accel_score").default(0.0)
    val absoluteFloor = double("absolute_floor").default(0.0)
    val volCap = double("vol_cap").default(0.0)
}

package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyMarketSentimentTable : Table(name = "daily_market_sentiment") {
    val tradeDate = date("trade_date")
    val signalBasis = varchar("signal_basis", 16)
    val sampleSize = integer("sample_size")
    val bullRatio = double("bull_ratio").default(0.0)
    val fftScore = double("fft_score").default(0.0)
    val residualScore = double("residual_score").default(0.0)
    val marketVol = double("market_vol").default(0.0)
    val volZ = double("vol_z").default(0.0)
    val accelZ = double("accel_z").default(0.0)
    val sentimentExposure = double("sentiment_exposure").default(0.0)
    val ratioNorm = double("ratio_norm").default(0.0)
    val volScore = double("vol_score").default(0.0)
    val accelScore = double("accel_score").default(0.0)
    val absoluteFloor = double("absolute_floor").default(0.0)
    val volCap = double("vol_cap").default(0.0)
    val sufficientHistory = bool("sufficient_history").default(false)
    val requiredHistory = integer("required_history")
    val reason = varchar("reason", 255).nullable()

    init {
        uniqueIndex("uk_daily_market_sentiment", tradeDate)
    }
}

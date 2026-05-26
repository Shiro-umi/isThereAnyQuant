package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyMarketSentimentStateTable : Table(name = "daily_market_sentiment_state") {
    val tradeDate = date("trade_date")
    val signalBasis = varchar("signal_basis", 16)
    val sampleCodesJson = text("sample_codes_json")
    val symbolStatesJson = text("symbol_states_json")
    val bullRatioHistoryJson = text("bull_ratio_history_json")
    val marketVolHistoryJson = text("market_vol_history_json")
    val accelHistoryJson = text("accel_history_json")
    val combinedHistoryJson = text("combined_history_json")
    val totalDays = integer("total_days")

    init {
        uniqueIndex("uk_daily_market_sentiment_state", tradeDate)
    }
}

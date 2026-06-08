package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyProfitPredictionSelectionTable : Table(name = "daily_profit_prediction_selection") {
    val tradeDate = date("trade_date")
    val targetDate = date("target_date")
    val tsCode = varchar("ts_code", 15)
    val modelId = varchar("model_id", 128).nullable()
    val modelScore = double("model_score")
    val selected = bool("selected")
    val targetWeight = double("target_weight")
    val sentimentExposure = double("sentiment_exposure")
    val selectionReason = varchar("selection_reason", 255).nullable()
    val candidateMode = varchar("candidate_mode", 32).nullable()

    init {
        uniqueIndex("uk_daily_profit_prediction_selection", tradeDate, targetDate, tsCode)
        index(
            "idx_daily_profit_prediction_target_selected_score",
            false,
            targetDate,
            selected,
            modelScore,
            tsCode
        )
        index(
            "idx_daily_profit_prediction_trade_selected_score",
            false,
            tradeDate,
            selected,
            modelScore,
            tsCode
        )
    }
}

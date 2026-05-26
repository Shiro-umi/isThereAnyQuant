package org.shiroumi.database.strategy.daily.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object DailyTargetPortfolioTable : Table(name = "daily_target_portfolio") {
    val tradeDate = date("trade_date")
    val targetDate = date("target_date")
    val tsCode = varchar("ts_code", 15)
    val selectionScore = double("selection_score").default(0.0)
    val selected = bool("selected")
    val targetWeight = double("target_weight")
    val sentimentExposure = double("sentiment_exposure")
    val selectionReason = varchar("selection_reason", 255).nullable()

    init {
        uniqueIndex("uk_daily_target_portfolio", tradeDate, targetDate, tsCode)
        index("idx_daily_target_portfolio_target_selected", false, targetDate, selected, selectionScore, tsCode)
    }
}

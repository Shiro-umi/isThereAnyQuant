package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate

/**
 * One row in sentiment_factor_daily: T-day fact factors plus raw T-day market labels.
 *
 * The research Study creates future Y labels dynamically from these raw fields; research
 * conclusions and intermediate states stay in files under ResearchContext.workspace.
 */
data class SentimentFactorDailyRecord(
    val tradeDate: LocalDate,
    val factors: Map<String, Double?>,
    val y1Raw: Double?,
    val y2Raw: Double?,
    val y3Raw: Double?,
    val yComposite: Double?,
    val notes: String? = null,
)

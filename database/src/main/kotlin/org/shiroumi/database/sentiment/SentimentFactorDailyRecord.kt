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
    /**
     * 量价因子族（VV/VP）的市场级基础量序列（先聚合后推导，全市场等权聚合）。
     * 不属于 38 情绪因子，单独承载；18 个 VV/VP 因子由 research 层在这两条序列上推导。
     */
    val vpmRet: Double? = null,   // 市场等权对数收益
    val vpmTurn: Double? = null,  // 市场等权换手率
)

package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate

data class TargetPosition(
    val tradeDate: LocalDate,
    val targetDate: LocalDate,
    val tsCode: String,
    val selectionScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val selectionReason: String?,
    /** Agent 量价分析买点限价（QFQ 口径）；null = 无买点。选股阶段为 null，由后续 agent 分析回填。 */
    val limitPrice: Double? = null,
)

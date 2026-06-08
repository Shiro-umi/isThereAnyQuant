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
)

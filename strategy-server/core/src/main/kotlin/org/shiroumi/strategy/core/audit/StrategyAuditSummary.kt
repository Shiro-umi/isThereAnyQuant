package org.shiroumi.strategy.core.audit

import kotlinx.datetime.LocalDate

data class StrategyAuditSummary(
    val tradeDate: LocalDate,
    val universeSize: Int,
    val signalPositiveCount: Int,
    val selectedCount: Int,
    val emptyReason: String?,
    val newlySelected: List<String>,
    val dropped: List<String>,
    val currentPositions: List<String>,
    val sentimentExposure: Double,
    val bullRatio: Double,
    val marketVol: Double,
    val fftScore: Double,
    val residualScore: Double,
    val accelZ: Double,
    val volZ: Double,
    val ratioNorm: Double,
    val volScore: Double,
    val accelScore: Double,
    val absoluteFloor: Double,
    val volCap: Double,
)

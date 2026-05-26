package org.shiroumi.database.common.compensation

import kotlinx.datetime.LocalDate

enum class CompensationTaskType(
    val defaultMaxAttempts: Int
) {
    DAILY_FACT_BY_TRADE_DATE(defaultMaxAttempts = 8),
    LIMIT_LIST_D_BY_TRADE_DATE(defaultMaxAttempts = 5),
    DAILY_FQ_BY_TRADE_DATE(defaultMaxAttempts = 5),
    STRATEGY_BY_TRADE_DATE(defaultMaxAttempts = 5),
}

enum class CompensationTaskStatus {
    PENDING,
    RUNNING,
    FAILED,
    EXHAUSTED,
    COMPLETED,
}

data class DataCompensationTask(
    val id: Long,
    val taskType: CompensationTaskType,
    val tradeDate: LocalDate,
    val payloadJson: String,
    val sourceStage: String,
    val status: CompensationTaskStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextRetryAtMillis: Long,
    val lastError: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long?,
)

package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.shiroumi.database.MAX_VARCHAR_LENGTH

object DataCompensationTaskTable : Table(name = "data_compensation_task") {
    val id = long("id").autoIncrement()
    val taskType = varchar("task_type", length = 64)
    val tradeDate = date("trade_date")
    val payloadJson = text("payload_json")
    val sourceStage = varchar("source_stage", length = MAX_VARCHAR_LENGTH)
    val status = varchar("status", length = 32)
    val attemptCount = integer("attempt_count")
    val maxAttempts = integer("max_attempts")
    val nextRetryAtMillis = long("next_retry_at_millis")
    val lastError = text("last_error").nullable()
    val createdAtMillis = long("created_at_millis")
    val updatedAtMillis = long("updated_at_millis")
    val completedAtMillis = long("completed_at_millis").nullable()

    init {
        uniqueIndex("uk_compensation_task_type_trade_date", taskType, tradeDate)
        index("idx_compensation_status_retry", false, status, nextRetryAtMillis)
    }

    override val primaryKey = PrimaryKey(id)
}

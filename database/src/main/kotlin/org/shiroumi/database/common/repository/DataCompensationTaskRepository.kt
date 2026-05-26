package org.shiroumi.database.common.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.common.compensation.CompensationTaskStatus
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.compensation.DataCompensationTask
import org.shiroumi.database.common.table.DataCompensationTaskTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction

object DataCompensationTaskRepository {

    fun upsertFailure(
        taskType: CompensationTaskType,
        tradeDate: LocalDate,
        sourceStage: String,
        payloadJson: String = "{}",
        maxAttempts: Int = taskType.defaultMaxAttempts,
        nextRetryAtMillis: Long,
        lastError: String,
    ) {
        val table = DataCompensationTaskTable
        val now = System.currentTimeMillis()
        stockDb.transaction(table, log = false) {
            table.insertIgnore {
                it[table.taskType] = taskType.name
                it[table.tradeDate] = tradeDate
                it[table.payloadJson] = payloadJson
                it[table.sourceStage] = sourceStage
                it[table.status] = CompensationTaskStatus.PENDING.name
                it[table.attemptCount] = 0
                it[table.maxAttempts] = maxAttempts
                it[table.nextRetryAtMillis] = nextRetryAtMillis
                it[table.lastError] = lastError
                it[table.createdAtMillis] = now
                it[table.updatedAtMillis] = now
                it[table.completedAtMillis] = null
            }
            table.update(
                where = {
                    (table.taskType eq taskType.name) and
                        (table.tradeDate eq tradeDate)
                }
            ) {
                it[table.payloadJson] = payloadJson
                it[table.sourceStage] = sourceStage
                it[table.status] = CompensationTaskStatus.PENDING.name
                it[table.attemptCount] = 0
                it[table.maxAttempts] = maxAttempts
                it[table.nextRetryAtMillis] = nextRetryAtMillis
                it[table.lastError] = lastError
                it[table.updatedAtMillis] = now
                it[table.completedAtMillis] = null
            }
        }
    }

    fun claimDueTasks(
        taskType: CompensationTaskType? = null,
        tradeDate: LocalDate? = null,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 100,
        ignoreSchedule: Boolean = false,
    ): List<DataCompensationTask> {
        val table = DataCompensationTaskTable
        return stockDb.transaction(table, log = false) {
            val rows = table.selectAll()
                .where {
                    val statusClause = table.status.inList(
                        listOf(
                            CompensationTaskStatus.PENDING.name,
                            CompensationTaskStatus.FAILED.name,
                        )
                    )
                    val retryClause = if (ignoreSchedule) null else (table.nextRetryAtMillis lessEq nowMillis)
                    val typeClause = taskType?.let { table.taskType eq it.name }
                    val dateClause = tradeDate?.let { table.tradeDate eq it }
                    listOfNotNull(statusClause, retryClause, typeClause, dateClause)
                        .reduce { acc, op -> acc and op }
                }
                .orderBy(table.tradeDate, SortOrder.ASC)
                .orderBy(table.id, SortOrder.ASC)
                .limit(limit)
                .map { it.toDomain() }

            if (rows.isNotEmpty()) {
                table.update(
                    where = { table.id inList rows.map { it.id } }
                ) {
                    it[status] = CompensationTaskStatus.RUNNING.name
                    it[updatedAtMillis] = nowMillis
                }
            }
            rows.map { it.copy(status = CompensationTaskStatus.RUNNING, updatedAtMillis = nowMillis) }
        }
    }

    fun markCompleted(id: Long, completedAtMillis: Long = System.currentTimeMillis()) {
        val table = DataCompensationTaskTable
        stockDb.transaction(table, log = false) {
            table.update(where = { table.id eq id }) {
                it[table.status] = CompensationTaskStatus.COMPLETED.name
                it[table.completedAtMillis] = completedAtMillis
                it[table.updatedAtMillis] = completedAtMillis
            }
        }
    }

    fun markRetry(
        id: Long,
        attemptCount: Int,
        nextRetryAtMillis: Long,
        lastError: String,
        exhausted: Boolean,
    ) {
        val table = DataCompensationTaskTable
        val now = System.currentTimeMillis()
        stockDb.transaction(table, log = false) {
            table.update(where = { table.id eq id }) {
                it[status] = if (exhausted) CompensationTaskStatus.EXHAUSTED.name else CompensationTaskStatus.FAILED.name
                it[table.attemptCount] = attemptCount
                it[table.nextRetryAtMillis] = nextRetryAtMillis
                it[table.lastError] = lastError
                it[table.updatedAtMillis] = now
            }
        }
    }

    fun countOutstanding(taskType: CompensationTaskType? = null): Long {
        val table = DataCompensationTaskTable
        return stockDb.transaction(table, log = false) {
            table.selectAll()
                .where {
                    val statusClause = table.status.inList(
                        listOf(
                            CompensationTaskStatus.PENDING.name,
                            CompensationTaskStatus.RUNNING.name,
                            CompensationTaskStatus.FAILED.name,
                            CompensationTaskStatus.EXHAUSTED.name,
                        )
                    )
                    val typeClause = taskType?.let { table.taskType eq it.name }
                    listOfNotNull(statusClause, typeClause).reduce { acc, op -> acc and op }
                }
                .count()
        }
    }

    fun findByTypeAndTradeDate(
        taskType: CompensationTaskType,
        tradeDate: LocalDate,
    ): DataCompensationTask? {
        val table = DataCompensationTaskTable
        return stockDb.transaction(table, log = false) {
            table.selectAll()
                .where {
                    (table.taskType eq taskType.name) and
                        (table.tradeDate eq tradeDate)
                }
                .limit(1)
                .singleOrNull()
                ?.toDomain()
        }
    }

    fun deleteAll() {
        val table = DataCompensationTaskTable
        stockDb.transaction(table, log = false) {
            table.deleteAll()
        }
    }

    private fun ResultRow.toDomain(): DataCompensationTask {
        val table = DataCompensationTaskTable
        return DataCompensationTask(
            id = this[table.id],
            taskType = CompensationTaskType.valueOf(this[table.taskType]),
            tradeDate = this[table.tradeDate],
            payloadJson = this[table.payloadJson],
            sourceStage = this[table.sourceStage],
            status = CompensationTaskStatus.valueOf(this[table.status]),
            attemptCount = this[table.attemptCount],
            maxAttempts = this[table.maxAttempts],
            nextRetryAtMillis = this[table.nextRetryAtMillis],
            lastError = this[table.lastError],
            createdAtMillis = this[table.createdAtMillis],
            updatedAtMillis = this[table.updatedAtMillis],
            completedAtMillis = this[table.completedAtMillis],
        )
    }
}

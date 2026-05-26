package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.compensation.DataCompensationTask
import org.shiroumi.database.common.repository.DataCompensationTaskRepository
import utils.logger
import kotlin.math.min
import kotlin.random.Random

private val logger by logger("DataCompensationTaskService")

data class CompensationDrainResult(
    val processed: Int,
    val completed: Int,
    val failed: Int,
)

class DataCompensationTaskService(
    private val dailyFactHandler: suspend (LocalDate) -> Unit,
    private val limitListDHandler: suspend (LocalDate) -> Unit = {},
    private val dailyFqHandler: suspend (LocalDate) -> Unit,
    private val strategyHandler: suspend (LocalDate) -> Unit,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {

    fun enqueueFailure(
        taskType: CompensationTaskType,
        tradeDate: LocalDate,
        sourceStage: String,
        lastError: String,
        payloadJson: String = "{}",
    ) {
        val nextRetryAtMillis = nowMillisProvider() + retryDelayMillis(attemptCount = 0)
        DataCompensationTaskRepository.upsertFailure(
            taskType = taskType,
            tradeDate = tradeDate,
            sourceStage = sourceStage,
            payloadJson = payloadJson,
            maxAttempts = taskType.defaultMaxAttempts,
            nextRetryAtMillis = nextRetryAtMillis,
            lastError = lastError,
        )
        logger.warning(
            "[补偿任务] ENQUEUED_TO_COMPENSATION_QUEUE | taskType=${taskType.name}, tradeDate=$tradeDate, " +
                "sourceStage=$sourceStage, nextRetryAt=$nextRetryAtMillis, error=$lastError"
        )
    }

    suspend fun drain(
        taskType: CompensationTaskType? = null,
        tradeDate: LocalDate? = null,
        limit: Int = 100,
        ignoreSchedule: Boolean = false,
    ): CompensationDrainResult {
        val now = nowMillisProvider()
        val tasks = DataCompensationTaskRepository.claimDueTasks(
            taskType = taskType,
            tradeDate = tradeDate,
            nowMillis = now,
            limit = limit,
            ignoreSchedule = ignoreSchedule,
        )
        var completed = 0
        var failed = 0
        tasks.forEach { task ->
            val nextAttempt = task.attemptCount + 1
            try {
                execute(task)
                DataCompensationTaskRepository.markCompleted(task.id, completedAtMillis = nowMillisProvider())
                completed += 1
                logger.info(
                    "[补偿任务] COMPLETED | taskType=${task.taskType.name}, tradeDate=${task.tradeDate}, " +
                        "attemptCount=$nextAttempt, sourceStage=${task.sourceStage}"
                )
            } catch (error: Exception) {
                val exhausted = nextAttempt >= task.maxAttempts
                val nextRetryAt = nowMillisProvider() + retryDelayMillis(attemptCount = nextAttempt)
                DataCompensationTaskRepository.markRetry(
                    id = task.id,
                    attemptCount = nextAttempt,
                    nextRetryAtMillis = nextRetryAt,
                    lastError = error.message ?: "unknown",
                    exhausted = exhausted,
                )
                failed += 1
                logger.error(
                    "[补偿任务] ${if (exhausted) "EXHAUSTED" else "FAILED"} | " +
                        "taskType=${task.taskType.name}, tradeDate=${task.tradeDate}, " +
                        "attemptCount=$nextAttempt/${task.maxAttempts}, sourceStage=${task.sourceStage}, " +
                        "nextRetryAt=$nextRetryAt, error=${error.message}"
                )
            }
        }
        return CompensationDrainResult(
            processed = tasks.size,
            completed = completed,
            failed = failed,
        )
    }

    fun hasOutstanding(taskType: CompensationTaskType? = null): Boolean =
        DataCompensationTaskRepository.countOutstanding(taskType) > 0

    private suspend fun execute(task: DataCompensationTask) {
        when (task.taskType) {
            CompensationTaskType.DAILY_FACT_BY_TRADE_DATE -> dailyFactHandler(task.tradeDate)
            CompensationTaskType.LIMIT_LIST_D_BY_TRADE_DATE -> limitListDHandler(task.tradeDate)
            CompensationTaskType.DAILY_FQ_BY_TRADE_DATE -> dailyFqHandler(task.tradeDate)
            CompensationTaskType.STRATEGY_BY_TRADE_DATE -> strategyHandler(task.tradeDate)
        }
    }

    private fun retryDelayMillis(attemptCount: Int): Long {
        val baseMinutes = when (attemptCount) {
            0 -> 1L
            1 -> 3L
            2 -> 10L
            3 -> 30L
            else -> 60L
        }
        val jitterMillis = Random.nextLong(from = 0L, until = 30_000L)
        return min(baseMinutes * 60_000L + jitterMillis, 60L * 60_000L + jitterMillis)
    }
}

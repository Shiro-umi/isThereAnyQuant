package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.server.runtime.update.defaultCompensationTaskService

fun main() = runBlocking {
    ConfigManager.load()

    val taskType = System.getProperty("quant.compensation.taskType")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(CompensationTaskType::valueOf)
    val tradeDate = System.getProperty("quant.compensation.tradeDate")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(LocalDate::parse)
    val ignoreSchedule = System.getProperty("quant.compensation.ignoreSchedule")
        ?.trim()
        ?.equals("true", ignoreCase = true)
        ?: true

    val result = defaultCompensationTaskService().drain(
        taskType = taskType,
        tradeDate = tradeDate,
        ignoreSchedule = ignoreSchedule,
    )

    println(
        "[补偿任务] drain 完成 | taskType=${taskType ?: "ALL"}, tradeDate=${tradeDate ?: "ALL"}, " +
            "ignoreSchedule=$ignoreSchedule, processed=${result.processed}, completed=${result.completed}, failed=${result.failed}"
    )
}

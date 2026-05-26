package org.shiroumi.server.runtime.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import utils.logger

private val schedulerLogger by logger("DataCompensationTaskScheduler")

class DataCompensationTaskScheduler(
    private val service: DataCompensationTaskService,
    private val intervalMillis: Long = 60_000L,
    private val onQueueDrained: (suspend (CompensationDrainResult) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            schedulerLogger.info("补偿任务调度器已启动")
            while (isActive) {
                runCatching { service.drain() }
                    .onSuccess { result ->
                        if (result.completed > 0) onQueueDrained?.invoke(result)
                    }
                    .onFailure { error ->
                        schedulerLogger.error("补偿任务调度失败: ${error.message}")
                    }
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

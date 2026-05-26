package org.shiroumi.server.runtime.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import model.DataUpdateStatus
import org.shiroumi.database.common.repository.SystemStatusRepository
import utils.logger
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val logger by logger("HistoricalDataUpdateScheduler")

/**
 * 盘后历史更新调度器。
 *
 * 它只处理三件事：
 * 1. 16:30 的定时调度
 * 2. 手动触发
 * 3. 状态持久化与状态变更回调
 *
 * 真正的更新步骤由 `HistoricalDataUpdateOrchestrator` 执行。
 */
class HistoricalDataUpdateScheduler(
    private val orchestrator: HistoricalDataUpdateOrchestrator,
    private val onStatusChanged: suspend (DataUpdateStatus) -> Unit,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null
    /**
     * 最近一次成功更新的时间戳。
     *
     * 这个字段不能只依赖外部 UI 持有，
     * 因为失败状态、重启恢复状态都需要继续保留“上一次成功是什么时候”。
     */
    private var lastSuccessfulUpdateTime: Long? = null

    suspend fun restoreLastStatus(): DataUpdateStatus? {
        val lastStatus = SystemStatusRepository.getDataUpdateStatus() ?: return null
        lastSuccessfulUpdateTime = lastStatus.lastUpdateTime
        return if (lastStatus.isUpdating()) {
            lastStatus.copy(
                state = DataUpdateStatus.STATE_FAILED,
                message = "服务器重启导致任务中断，请手动重试"
            )
        } else {
            lastStatus
        }
    }

    fun startScheduler() {
        scope.launch {
            logger.info("盘后历史更新调度器已启动")
            while (isActive) {
                val now = LocalDateTime.now(zoneId)
                val target = now.withHour(16).withMinute(30).withSecond(0)
                val delayMillis = when {
                    now.isBefore(target) -> ChronoUnit.MILLIS.between(now, target)
                    now.isAfter(target) -> ChronoUnit.MILLIS.between(now, target.plusDays(1))
                    else -> 0
                }

                logger.info("下次历史数据更新时间: ${target.plusDays(if (now.isAfter(target)) 1 else 0)}")
                delay(delayMillis)
                if (isActive) performUpdate()
            }
        }
    }

    fun stopScheduler() {
        scope.coroutineContext[Job]?.cancel()
        updateJob?.cancel()
    }

    suspend fun triggerUpdate(): Boolean {
        if (updateJob?.isActive == true) return false
        performUpdate()
        return true
    }

    fun getTimeUntilNextUpdate(): Long {
        val now = LocalDateTime.now(zoneId)
        val target = now.withHour(16).withMinute(30).withSecond(0)
        return when {
            now.isBefore(target) -> ChronoUnit.MILLIS.between(now, target)
            else -> ChronoUnit.MILLIS.between(now, target.plusDays(1))
        }
    }

    private suspend fun performUpdate() {
        if (updateJob?.isActive == true) return

        val startTime = System.currentTimeMillis()
        onStatusChanged(
            DataUpdateStatus(
                state = DataUpdateStatus.STATE_UPDATING,
                lastUpdateTime = lastSuccessfulUpdateTime,
                currentUpdateStartTime = startTime,
                currentStep = "准备开始",
                progress = 0,
                message = "开始更新数据..."
            )
        )

        updateJob = scope.launch {
            try {
                orchestrator.execute { step, progress ->
                    onStatusChanged(
                        DataUpdateStatus(
                            state = DataUpdateStatus.STATE_UPDATING,
                            lastUpdateTime = lastSuccessfulUpdateTime,
                            currentUpdateStartTime = startTime,
                            currentStep = step,
                            progress = progress,
                            message = ""
                        )
                    )
                }

                val endTime = System.currentTimeMillis()
                lastSuccessfulUpdateTime = endTime
                val duration = (endTime - startTime) / 1000
                onStatusChanged(
                    DataUpdateStatus(
                        state = DataUpdateStatus.STATE_COMPLETED,
                        lastUpdateTime = endTime,
                        currentUpdateStartTime = startTime,
                        progress = 100,
                        message = "更新完成，耗时 ${duration} 秒"
                    )
                )
                delay(3000)
                onStatusChanged(
                    DataUpdateStatus(
                        state = DataUpdateStatus.STATE_IDLE,
                        lastUpdateTime = endTime,
                        message = ""
                    )
                )
            } catch (e: Exception) {
                onStatusChanged(
                    DataUpdateStatus(
                        state = DataUpdateStatus.STATE_FAILED,
                        lastUpdateTime = lastSuccessfulUpdateTime,
                        currentUpdateStartTime = startTime,
                        message = "更新失败: ${e.message}"
                    )
                )
            }
        }

        updateJob?.join()
    }
}

package org.shiroumi.server.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import model.DataUpdateStatus
import model.dataprovider.ExecutionPhase
import model.dataprovider.UpdateCause
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.server.dataprovider.bootstrap.DataProviderBootstrap
import org.shiroumi.server.runtime.update.HistoricalDataUpdateOrchestrator
import org.shiroumi.server.runtime.update.DataCompensationTaskScheduler
import org.shiroumi.server.runtime.update.HistoricalDataUpdateScheduler
import utils.logger

private val logger by logger("DataUpdateService")

/**
 * 数据更新服务
 * 管理定时更新任务和状态
 */
object DataUpdateService {
    
    private val _status = MutableStateFlow(DataUpdateStatus())
    val status: StateFlow<DataUpdateStatus> = _status.asStateFlow()
    
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    
    private val scheduler by lazy {
        HistoricalDataUpdateScheduler(
            orchestrator = HistoricalDataUpdateOrchestrator(),
            onStatusChanged = { newStatus -> updateStatus(newStatus) }
        )
    }
    private val compensationScheduler by lazy {
        DataCompensationTaskScheduler(
            service = org.shiroumi.server.runtime.update.defaultCompensationTaskService(),
            onQueueDrained = { result -> onCompensationDrained(result) }
        )
    }

    init {
        // 从数据库回复最后记录的状态
        try {
            val lastStatus = runBlocking { scheduler.restoreLastStatus() }
            if (lastStatus != null) {
                val resolved = if (lastStatus.state == DataUpdateStatus.STATE_FAILED &&
                    !org.shiroumi.server.runtime.update.defaultCompensationTaskService().hasOutstanding()
                ) {
                    logger.info("恢复状态为 failed 但补偿队列已清空，修正为 completed")
                    lastStatus.copy(
                        state = DataUpdateStatus.STATE_COMPLETED,
                        message = "补偿任务已完成"
                    )
                } else {
                    lastStatus
                }
                val enriched = enrichStatus(resolved)
                _status.value = enriched
                runBlocking {
                    org.shiroumi.database.common.repository.SystemStatusRepository.saveDataUpdateStatus(enriched)
                }
                logger.info("已从数据库恢复数据更新状态: ${enriched.state}, 最后更新时间: ${enriched.lastUpdateTime}")
            }
        } catch (e: Exception) {
            logger.error("恢复数据更新状态失败: ${e.message}")
        }
        
        // 启动心跳：每 60 秒重新计算并广播一次 show/hide 状态，
        // 确保前端始终由后端驱动是否展示指示器
        heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                delay(60_000)
                // 使用 update 保证原子读-改-写，避免与 updateStatus() 竞态覆盖
                _status.update { current ->
                    enrichStatus(current)
                }
                broadcastStatus(_status.value)
            }
        }
    }
    
    /**
     * 启动定时任务调度器
     * 每天16:30执行数据更新
     */
    fun startScheduler() {
        scheduler.startScheduler()
        compensationScheduler.start()
    }
    
    /**
     * 停止调度器
     */
    fun stopScheduler() {
        scheduler.stopScheduler()
        compensationScheduler.stop()
        heartbeatJob?.cancel()
        logger.info("数据更新定时任务调度器已停止")
    }
    
    /**
     * 手动触发更新
     */
    suspend fun triggerUpdate(): Boolean {
        return scheduler.triggerUpdate()
    }
    
    private suspend fun onCompensationDrained(result: org.shiroumi.server.runtime.update.CompensationDrainResult) {
        val current = _status.value
        if (current.state != DataUpdateStatus.STATE_FAILED) return
        val service = org.shiroumi.server.runtime.update.defaultCompensationTaskService()
        if (service.hasOutstanding()) return
        logger.info("补偿任务全部完成，恢复状态为 completed (补偿结果: completed=${result.completed})")
        updateStatus(
            current.copy(
                state = DataUpdateStatus.STATE_COMPLETED,
                lastUpdateTime = System.currentTimeMillis(),
                message = "补偿任务完成后恢复"
            )
        )
    }

    /**
     * 更新状态并广播
     */
    private suspend fun updateStatus(newStatus: DataUpdateStatus) {
        val enriched = enrichStatus(newStatus)
        _status.value = enriched
        // 持久化到数据库
        org.shiroumi.database.common.repository.SystemStatusRepository.saveDataUpdateStatus(enriched)

        if (enriched.state == DataUpdateStatus.STATE_COMPLETED) {
            runCatching {
                DataProviderBootstrap.stockCatalogSnapshotService.refresh()
            }.onFailure { error ->
                logger.error("盘后数据完成后刷新股票目录快照失败: ${error.message}")
            }

            runCatching {
                DataLayerBootstrap.refreshDailyUniverse()
            }.onFailure { error ->
                logger.error("盘后数据完成后刷新新数据层 DAY 快照失败: ${error.message}")
            }

            runCatching {
                DataProviderBootstrap.updateService.refreshAllForPhase(
                    phase = ExecutionPhase.OFF_MARKET,
                    cause = UpdateCause.POST_MARKET_DATA_READY
                )
            }.onFailure { error ->
                logger.error("盘后数据完成后刷新 provider 失败: ${error.message}")
            }
        }
        
        broadcastStatus(enriched)
    }
    
    /**
     * 为状态补充 timeUntilNextUpdate 和 shouldShowIndicator，
     * 使前端完全由后端驱动是否展示指示器。
     */
    private fun enrichStatus(status: DataUpdateStatus): DataUpdateStatus {
        val timeUntilNext = scheduler.getTimeUntilNextUpdate()
        return status.copy(
            timeUntilNextUpdate = timeUntilNext,
            shouldShowIndicator = computeShouldShow(status, timeUntilNext)
        )
    }
    
    private fun computeShouldShow(status: DataUpdateStatus, timeUntilNext: Long): Boolean {
        val fiveMinutes = 5 * 60 * 1000L
        val now = System.currentTimeMillis()
        return when (status.state) {
            DataUpdateStatus.STATE_UPDATING -> true
            DataUpdateStatus.STATE_FAILED,
            DataUpdateStatus.STATE_COMPLETED -> {
                val lastUpdate = status.lastUpdateTime
                lastUpdate != null && (now - lastUpdate < fiveMinutes)
            }
            DataUpdateStatus.STATE_IDLE -> {
                val lastUpdate = status.lastUpdateTime
                val recentlyUpdated = lastUpdate != null && (now - lastUpdate < fiveMinutes)
                val aboutToUpdate = timeUntilNext in 1..fiveMinutes
                recentlyUpdated || aboutToUpdate
            }
            else -> false
        }
    }
    
    private suspend fun broadcastStatus(status: DataUpdateStatus) {
        org.shiroumi.server.websocket.AppWebSocketConnectionManager.broadcast(
            model.ws.WsEvent(
                topic = model.ws.WsTopic.DATA_UPDATE,
                action = model.ws.WsAction.UPDATE,
                payload = kotlinx.serialization.json.Json.encodeToString(status)
            )
        )
    }
    
    /**
     * 获取当前状态
     */
    fun getCurrentStatus(): DataUpdateStatus = _status.value
    
    /**
     * 获取距离下次更新的时间（毫秒）
     */
    fun getTimeUntilNextUpdate(): Long {
        return scheduler.getTimeUntilNextUpdate()
    }

    suspend fun drainCompensationQueue(
        taskType: org.shiroumi.database.common.compensation.CompensationTaskType? = null,
        tradeDate: kotlinx.datetime.LocalDate? = null,
    ) = org.shiroumi.server.runtime.update.defaultCompensationTaskService().drain(taskType, tradeDate)
}

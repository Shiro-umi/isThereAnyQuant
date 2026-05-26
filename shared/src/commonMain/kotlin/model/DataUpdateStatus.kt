package model

import kotlinx.serialization.Serializable

/**
 * 数据更新状态
 * 用于前后端通信和状态管理
 */
@Serializable
data class DataUpdateStatus(
    /** 状态: idle(空闲), updating(更新中), completed(完成), failed(失败) */
    val state: String = "idle",
    
    /** 最后成功更新时间戳 */
    val lastUpdateTime: Long? = null,
    
    /** 当前更新开始时间戳（如果在更新中） */
    val currentUpdateStartTime: Long? = null,
    
    /** 当前更新步骤描述 */
    val currentStep: String = "",
    
    /** 进度 0-100 */
    val progress: Int = 0,
    
    /** 消息/错误信息 */
    val message: String = "",

    /** 距离下次更新的时间（毫秒），由后端计算 */
    val timeUntilNextUpdate: Long? = null,

    /** 是否应显示更新指示器，由后端根据当前周期决定 */
    val shouldShowIndicator: Boolean = false,
) {
    companion object {
        const val STATE_IDLE = "idle"
        const val STATE_UPDATING = "updating"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
    }
    
    /** 是否正在更新中 */
    fun isUpdating(): Boolean = state == STATE_UPDATING
    
    /** 是否可以提交任务 */
    fun canSubmitTask(): Boolean = state == STATE_IDLE
}

/**
 * WebSocket消息
 */
@Serializable
data class DataUpdateMessage(
    /** 消息类型 */
    val type: String,
    
    /** 时间戳 */
    val timestamp: Long = 0,
    
    /** 状态数据 */
    val status: DataUpdateStatus? = null
) {
    companion object {
        const val TYPE_STATUS = "status"           // 状态更新
        const val TYPE_STARTED = "started"         // 开始更新
        const val TYPE_PROGRESS = "progress"       // 进度更新
        const val TYPE_COMPLETED = "completed"     // 完成
        const val TYPE_FAILED = "failed"           // 失败
    }
}

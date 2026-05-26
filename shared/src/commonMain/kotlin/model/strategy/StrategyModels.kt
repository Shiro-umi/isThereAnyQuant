package model.strategy

import kotlinx.serialization.Serializable

/**
 * 策略定义
 * 前后端共享模型
 */
@Serializable
data class StrategyDefinition(
    val id: Int,
    val code: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 策略选股任务
 * 前后端共享模型
 */
@Serializable
data class StrategyStockPickTask(
    val id: String,
    val strategyCode: String,
    val strategyName: String,
    val tradeDate: String,
    val status: StrategyTaskStatus,
    val createdAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)

/**
 * 策略任务状态
 * 前后端共享模型
 */
@Serializable
enum class StrategyTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * 策略选股结果（完整）
 * 前后端共享模型
 */
@Serializable
data class StrategyStockPickResult(
    val stocks: List<StockPickItem>,
    val summary: StrategySummary? = null,
    val rawOutput: String? = null  // 原始输出文本（用于调试）
)

/**
 * 单只股票的选股结果
 * 前后端共享模型
 */
@Serializable
data class StockPickItem(
    val code: String,           // 股票代码
    val name: String,           // 股票名称
    val themeName: String,      // 所属题材
    val pctChg10d: Float,       // 10日涨跌幅（小数，如 0.15 表示 15%）
    val limitUpCount10d: Int,   // 10日涨停次数
    val pctChgToday: Float,     // 当日涨跌幅
    val close: Float,           // 收盘价
    val tradeDate: String,      // 交易日期
    val sparkline: List<Float> = emptyList()  // 最近30天收盘价，用于迷你趋势图
)

/**
 * 选股结果汇总
 * 前后端共享模型
 */
@Serializable
data class StrategySummary(
    val totalCount: Int,                     // 选中股票总数
    val themeDistribution: Map<String, Int>, // 题材分布
    val avgPctChg10d: Float,                 // 平均10日涨幅
    val tradeDate: String                    // 选股日期
)

/**
 * 前端展示的选股历史项
 * 前后端共享模型
 */
@Serializable
data class StrategyPickHistoryItem(
    val id: String,
    val strategyCode: String,
    val strategyName: String,
    val tradeDate: String,
    val status: StrategyTaskStatus,
    val stockCount: Int,
    val createdAt: Long,
    val completedAt: Long? = null
)

// ============================================
// API 请求/响应模型
// ============================================

/**
 * 提交策略选股请求
 */
@Serializable
data class SubmitStrategyRequest(
    val strategyCode: String
)

/**
 * 提交策略选股响应
 */
@Serializable
data class SubmitStrategyResponse(
    val taskId: String,
    val strategyCode: String,
    val strategyName: String,
    val tradeDate: String,
    val status: StrategyTaskStatus,
    val isExisting: Boolean,
    val websocketUrl: String
)

/**
 * 策略列表响应
 */
@Serializable
data class StrategyListResponse(
    val strategies: List<StrategyDefinition>
)

/**
 * 策略选股历史列表响应
 */
@Serializable
data class StrategyTaskListResponse(
    val tasks: List<StrategyPickHistoryItem>
)

/**
 * 策略选股详情响应
 */
@Serializable
data class StrategyTaskDetailResponse(
    val task: StrategyStockPickTask,
    val result: StrategyStockPickResult?
)

// ============================================
// WebSocket 流式事件
// ============================================

/**
 * 策略选股流式事件
 * 用于 WebSocket 实时推送进度
 */
@Serializable
sealed class StrategyStreamEvent {
    @Serializable
    data class TaskCreated(val taskId: String, val message: String) : StrategyStreamEvent()
    
    @Serializable
    data class Progress(val step: String, val message: String) : StrategyStreamEvent()
    
    @Serializable
    data class StockFound(val stock: StockPickItem, val currentCount: Int) : StrategyStreamEvent()
    
    @Serializable
    data class Completed(val summary: StrategySummary) : StrategyStreamEvent()
    
    @Serializable
    data class Error(val message: String) : StrategyStreamEvent()
}

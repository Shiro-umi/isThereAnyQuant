package model.ws

import kotlinx.serialization.Serializable
import model.CandleData
import model.agent.AgentAnalysisContext
import model.candle.CandlePeriod
import model.candle.MarketStatus

/**
 * 全局 WebSocket 多路复用协议 - Server 下发的统一事件信封
 *
 * 作用：将原本分散的多个 WebSocket 路由（如数据更新、AI任务流、策略选股流）统一到一个长连接中。
 * 服务器会将所有业务数据打包在此信封中发送给客户端。
 */
@Serializable
data class WsEvent(
    val topic: WsTopic,       // 业务主题，用于前端识别将 payload 分发给哪个业务模块
    val action: WsAction,     // 动作类型，标识该消息的性质（更新、完成、错误等）
    val targetId: String? = null, // 目标 ID，如果是针对特定任务的流，通过此字段标识，方便前端判断是否属于自己关注的页面
    val payload: String? = null // 具体的业务 JSON 数据，由前端各业务模块自行反序列化
)

/**
 * 业务主题定义
 * 决定了该事件应该被路由到前端的哪个 ViewModel 或 Service
 */
@Serializable
enum class WsTopic {
    DATA_UPDATE,        // 基础数据更新状态广播
    STRATEGY_TASK_LIST, // 策略选股任务列表更新通知
    STRATEGY_STREAM,    // 特定策略任务的流式执行日志
    SYSTEM,             // 系统级控制消息
    AGENT_STREAM,       // 特定 Agent 会话的流式状态更新
    AGENT_SESSION,      // Agent 会话创建/关闭通知
    CANDLE_DATA,        // K线完整视图推送
    MARKET_STATUS,      // 市场开收盘状态变化通知
    STOCK_LIST_UPDATE,  // 股票列表实时行情更新
    INTRADAY_SNAPSHOT,       // 分时快照数据推送
    STRATEGY_POSITIONS,      // 策略持仓 + 下一交易日选股轻量状态（连接建立时 SYNC + 盘中 UPDATE）
    STRATEGY_POSITION_TRACKING  // 策略持仓跟踪时间线（预计算全量 + 盘中 UPDATE）
}

/**
 * 动作类型
 */
@Serializable
enum class WsAction {
    SYNC,       // 全量同步（通常用于初次连接时下发完整状态）
    UPDATE,     // 增量更新（如任务进度推进一步，或列表状态有变更）
    COMPLETE,   // 任务或流程完成
    ERROR       // 发生错误
}

/**
 * 全局 WebSocket 多路复用协议 - Client 上报的控制指令
 *
 * 作用：客户端通过此协议告诉服务器"我当前对哪些数据感兴趣"。
 * 例如：当用户进入某个任务的详情页时，发送订阅指令，服务器便会开始推送该任务的流日志。
 */
@Serializable
data class WsCommand(
    val command: CommandType,    // 指令类型
    val targetId: String? = null, // 目标 ID（通常是 taskId），用于精确订阅特定的数据流
    val payload: String? = null,  // 附加数据（可选），如 Agent 创建时的工作目录或 Prompt 内容
    val commandSeq: Long? = null  // 客户端命令序号，用于后端异步收敛
)

/**
 * 控制指令类型
 */
@Serializable
enum class CommandType {
    SUBSCRIBE_STRATEGY,     // 订阅特定策略任务的日志流，要求带 targetId
    UNSUBSCRIBE_STRATEGY,   // 取消订阅特定策略任务日志，退出页面时调用
    FORCE_REFRESH_LIST,     // 客户端主动要求刷新某列表（预留指令）
    AGENT_CREATE_SESSION,   // 创建新 Agent 会话，payload 为工作目录
    AGENT_CLOSE_SESSION,    // 关闭 Agent 会话，targetId 为 sessionId
    SUBSCRIBE_AGENT,        // 订阅特定 Agent 会话的状态流，要求带 targetId (sessionId)
    UNSUBSCRIBE_AGENT,      // 取消订阅 Agent 会话状态流
    AGENT_SEND_PROMPT,      // 发送 Prompt 给 Agent，targetId 为 sessionId，payload 为消息内容
    AGENT_APPROVE_TOOL,     // 批准工具调用，targetId 为 sessionId，payload 为 requestId
    AGENT_REJECT_TOOL,      // 拒绝工具调用，targetId 为 sessionId，payload 为 requestId
    AGENT_STOP,             // 主动停止 Agent 当前任务，targetId 为 sessionId
    AGENT_RESUME,           // 在已中断的会话上以原上下文继续生成，targetId 为 sessionId
    SUBSCRIBE_CANDLE,            // 订阅 K 线完整视图，targetId 为股票代码，payload 为 CandleSubscribeRequest
    UNSUBSCRIBE_CANDLE,          // 取消订阅 K 线完整视图，targetId 为股票代码
    SET_STOCK_LIST_CONTEXT,      // 设置当前页面可见股票上下文，payload 为 tsCode 列表（如 "000001.SZ,600000.SH"）
    SUBSCRIBE,              // 通用 Topic 订阅，targetId 为 WsTopic 名称
    UNSUBSCRIBE,            // 通用 Topic 取消订阅，targetId 为 WsTopic 名称
    SET_TRACKING_FOLLOW_START_DATE, // 设置持仓跟踪最早跟随日校准日期；payload 为空/null/空字符串表示清除
}


/**
 * Agent 运行状态枚举，前后端及 agent 模块共用
 */
@Serializable
enum class AgentStatus {
    IDLE,
    THINKING,
    AWAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    ERROR
}

@Serializable
enum class ToolCallStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Agent 日志类型
 */
@Serializable
enum class AgentLogType {
    THOUGHT,
    TOOL_CALL,
    OUTPUT
}

/**
 * Agent 实时执行日志条目
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Serializable
data class AgentLogEntry(
    val id: String = kotlin.uuid.Uuid.random().toString(),
    val type: AgentLogType,
    val content: String = "",
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolCallId: String? = null
)

/**
 * Agent 中断原因，仅在生成被异常打断时由后端填入 [AgentStatePayload.interruption]。
 */
@Serializable
enum class AgentInterruptionReason {
    IDLE_TIMEOUT,        // 事件流空闲超过阈值
    MAX_TURN_REQUESTS,   // Claude Code 触发最大工具调用轮次或 Token 上限
    REFUSAL,             // 模型拒答
    USER_CANCELLED,      // 用户主动 stop
    PROCESS_ERROR        // ACP 进程或通信异常
}

/**
 * Agent 中断信息：用于在前端 chat 流中渲染"继承上下文继续"按钮。
 */
@Serializable
data class AgentInterruption(
    val reason: AgentInterruptionReason,
    val message: String,            // 给用户阅读的自然语言说明
    val resumable: Boolean = true   // 是否允许通过 AGENT_RESUME 续写
)

/**
 * Agent 会话完整快照。
 *
 * 仅用于以下两种场景：
 * - SUBSCRIBE_AGENT 订阅瞬间下发当前累积状态
 * - COMPLETE / ERROR 终态时随 [AgentStreamPayload.Delta.snapshot] 一并下发，作为前端聚合丢帧时的兜底
 *
 * 中间 UPDATE 一律走 [AgentStreamPayload.Delta]，避免每帧重发数万字符的累积 output。
 */
@Serializable
data class AgentStatePayload(
    val sessionId: String,
    val status: AgentStatus,
    val thinking: String = "",
    val output: String = "",
    val activeToolName: String? = null,
    val pendingApprovalIds: List<String> = emptyList(),
    val pendingApprovalTools: List<String> = emptyList(),
    val error: String? = null,
    val logs: List<AgentLogEntry> = emptyList(),
    val context: AgentAnalysisContext? = null,
    val interruption: AgentInterruption? = null
)

/**
 * AGENT_STREAM topic 的统一 payload 信封。
 *
 * 设计原则：
 * - 服务端 [model.ws.AgentStatePayload] 的 output/thinking/logs 字段是累积视图，若每帧整帧下发，
 *   单帧体积会随报告增长到数十至上百 KB，公网慢链路必然触发 5s 写超时被动断连，
 *   且 ktor 出站邮箱的 conflate 折叠会把中间累积帧覆盖，最终用户只能看到终态那一帧。
 * - 因此协议明确分为两种载荷：
 *   - [Snapshot]：完整状态视图，仅 SYNC（订阅 / 重连恢复）与终态 Delta 的 snapshot 字段使用
 *   - [Delta]：仅传输差量，覆盖中间 UPDATE 帧，让"output 一边生成一边到达"成立
 */
@Serializable
sealed class AgentStreamPayload {
    /**
     * 完整状态快照，用于 SYNC 帧或终态兜底。
     */
    @Serializable
    @kotlinx.serialization.SerialName("snapshot")
    data class Snapshot(val state: AgentStatePayload) : AgentStreamPayload()

    /**
     * 增量更新。
     *
     * 字段语义：
     * - 所有可空字段为 `null` 表示「本帧不涉及该维度」；非 null 即为变化。
     * - [outputAppend] / [thinkingAppend] 是相对前一帧累积值的追加文本，前端应直接 `currentOutput + outputAppend`。
     * - [newLogs] 是新增加的日志条目（按时间顺序）。
     * - [logPatches] 是对已存在条目（按 [AgentLogEntry.id] 索引）的字段级更新，用于工具调用从 RUNNING 变 COMPLETED 等场景。
     * - [appendToLastLog] 是把内容追加到列表中最后一条相同 type 的条目，对应 StateManager 里把 thinking/output chunk 累加到尾部同类条目的语义。
     * - [activeToolNamePresent]=true 时，[activeToolName] 字段才生效；为 null + present 表示"清空 activeTool"，
     *   为 null + 未 present 表示"本帧不动 activeTool"。
     * - [pendingApprovals] 给出则全量覆盖（pending approvals 数量极少，全量更简单且不会出错）。
     * - [snapshot] 仅在 COMPLETE / ERROR 终态帧里附带，前端用它做最终对账，
     *   防止丢帧或乱序导致本地聚合状态偏离真实终态。
     */
    @Serializable
    @kotlinx.serialization.SerialName("delta")
    data class Delta(
        val sessionId: String,
        val status: AgentStatus? = null,
        val outputAppend: String? = null,
        val thinkingAppend: String? = null,
        val newLogs: List<AgentLogEntry> = emptyList(),
        val logPatches: List<AgentLogPatch> = emptyList(),
        val appendToLastLog: AppendToLastLog? = null,
        val activeToolNamePresent: Boolean = false,
        val activeToolName: String? = null,
        val pendingApprovals: PendingApprovalsPatch? = null,
        val error: String? = null,
        val interruption: AgentInterruption? = null,
        val context: AgentAnalysisContext? = null,
        val snapshot: AgentStatePayload? = null
    ) : AgentStreamPayload()
}

/**
 * 对单条 [AgentLogEntry] 的字段级补丁。`null` 字段表示不更新；非 null 字段覆盖原值。
 * 用于工具调用从 RUNNING → COMPLETED/FAILED、补全 toolInput/toolOutput 等场景。
 */
@Serializable
data class AgentLogPatch(
    val logId: String,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolCallId: String? = null
)

/**
 * 把内容追加到列表中"最后一条同 type 条目"的尾部。
 * 对应 StateManager 中处理 AgentThoughtChunk / AgentMessageChunk 时的"累加到尾部"逻辑。
 */
@Serializable
data class AppendToLastLog(
    val type: AgentLogType,
    val content: String
)

/**
 * pendingApprovals 列表的全量替换补丁。
 * 选择全量替换是因为同时挂起的审批通常 ≤ 2 个，做精细 add/remove 反而引入顺序问题。
 */
@Serializable
data class PendingApprovalsPatch(
    val ids: List<String>,
    val toolNames: List<String>
)

// ==================== 蜡烛图数据 WebSocket 协议 ====================

/**
 * 蜡烛图数据订阅请求
 * 客户端通过 WsCommand 的 payload 字段发送此请求
 */
@Serializable
data class CandleSubscribeRequest(
    val tsCode: String,                              // 股票代码
    val period: CandlePeriod = CandlePeriod.DAY,    // K线周期（决定是否触发实时轮询）
    val limit: Int? = null,                          // 限制返回的蜡烛图数量（优先级高于日期范围）
    val startDate: String? = null,                   // 开始日期 (yyyy-MM-dd)
    val endDate: String? = null,                     // 结束日期 (yyyy-MM-dd)
    val useAdjusted: Boolean = true,                 // 是否使用前复权数据
    val requestSeq: Long? = null                     // 客户端请求序号，用于消除乱序响应
)

/**
 * 蜡烛图数据响应载荷
 *
 * 语义约束：
 * - `CANDLE_DATA` 是 K 线唯一权威 topic
 * - 服务端返回的是当前完整可消费视图
 * - 前端不再需要自行拼接历史窗口和实时窗口
 */
@Serializable
data class CandleDataPayload(
    val tsCode: String,                    // 股票代码
    val candles: List<CandleData>,          // 蜡烛图数据列表（使用统一的 CandleData）
    val totalCount: Int,                   // 总数据条数
    val requestParams: CandleSubscribeRequest? = null  // 原始请求参数（用于校验）
)

/**
 * 蜡烛图数据错误响应
 */
@Serializable
data class CandleErrorPayload(
    val tsCode: String,
    val errorCode: CandleErrorCode,
    val message: String,
    val requestSeq: Long? = null
)

/**
 * 蜡烛图数据错误码
 */
@Serializable
enum class CandleErrorCode {
    INVALID_STOCK_CODE,     // 无效的股票代码
    DATE_RANGE_INVALID,     // 日期范围无效
    DATA_NOT_FOUND,         // 未找到数据
    SYSTEM_WARMING_UP,      // 服务尚在启动预热阶段
    PROVIDER_NOT_READY,     // 对应 Provider 尚未就绪
    CACHE_ERROR,            // 缓存错误
    DATABASE_ERROR,         // 数据库错误
    UNKNOWN_ERROR           // 未知错误
}

/**
 * 市场开收盘状态变化推送载荷
 * topic=MARKET_STATUS, action=UPDATE
 */
@Serializable
data class MarketStatusPayload(
    val status: MarketStatus,
    val nextStatusTime: String? = null  // 下次状态切换时间，格式 "HH:mm"
)

/**
 * 股票列表实时行情推送载荷
 * topic=STOCK_LIST_UPDATE, action=UPDATE
 */
@Serializable
data class StockListUpdatePayload(
    val stocks: List<StockInfoUpdate>,
    val timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
)

/**
 * 股票列表更新条目（仅包含关键行情字段，减少流量）
 */
@Serializable
data class StockInfoUpdate(
    val code: String,
    val latestPrice: Float,
    val changeAmount: Float,
    val changePercent: Float,
    val volume: Float,
    val turnover: Float
)

// ==================== 盘中快照 WebSocket 协议 ====================

/**
 * 市场情绪快照
 */
@Serializable
data class MarketSentimentSnapshot(
    val tradeDate: String,
    val signalBasis: String,
    val sampleSize: Int,
    val bullRatio: Double,
    val fftScore: Double,
    val residualScore: Double,
    val marketVol: Double,
    val volZ: Double,
    val accelZ: Double,
    val sentimentExposure: Double,
    val ratioNorm: Double,
    val volScore: Double,
    val accelScore: Double,
    val absoluteFloor: Double,
    val volCap: Double,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val reason: String? = null
)

/**
 * 股票因子快照
 */
@Serializable
data class StockFactorSnapshot(
    val tradeDate: String,
    val tsCode: String,
    val signalBasis: String,
    val executionBasis: String,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val executionOpen: Double,
    val executionClose: Double,
    val hfqFactor: Double,
    val ema10: Double,
    val ema30: Double,
    val emaBull: Boolean,
    val atr14: Double,
    val signal: Boolean,
    val momentum20: Double,
    val volRatio520: Double,
    val amomCombined: Double,
    val rankScore: Double
)

/**
 * 策略组合条目。
 *
 * selected=true 表示 tradeDate 当天选出、用于下一交易日执行的标的；
 * 不能直接等同于 tradeDate 当天已经持有。
 */
@Serializable
data class TargetPosition(
    val tsCode: String,
    val name: String? = null,
    val rankScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val priceChangePct: Double,
    val volumeRatio: Double,
    val postMarketSelected: Boolean? = null,
    val postMarketWeight: Double? = null,
    val action: String? = null,
    val actionReason: String? = null
)

/**
 * 计算耗时监控指标
 */
@Serializable
data class CalcMetrics(
    val dataFetchMs: Long = 0,
    val factorCalcMs: Long = 0,
    val sentimentCalcMs: Long = 0,
    val portfolioGenMs: Long = 0,
    val persistMs: Long = 0,
    val totalMs: Long = 0,
    val stockCount: Int = 0
)

/**
 * 盘中快照数据载荷
 * topic=INTRADAY_SNAPSHOT
 * - action=SYNC: 首次订阅时返回当前完整快照
 * - action=UPDATE: 盘中计算后持续推送最新快照
 */
@Serializable
data class IntradaySnapshotPayload(
    val timestamp: Long,
    val sentiment: MarketSentimentSnapshot,
    val portfolio: List<TargetPosition>,
    val topStocks: List<StockFactorSnapshot>,
    val calcMetrics: CalcMetrics
)

// ==================== 策略持仓 WebSocket 协议 ====================

@Serializable
enum class PositionSource {
    HISTORICAL_AUDIT,
    DAILY_AUDIT_COMPLETE,
    INTRADAY_REALTIME
}

@Serializable
data class StrategySelectionSnapshot(
    val tsCode: String,
    val modelScore: Double,
)

@Serializable
data class StrategyPositionSnapshot(
    /** 当前交易日。 */
    val tradeDate: String,
    /** 当天实际持仓，来自 targetDate=tradeDate 的已选组合或审计快照。 */
    val currentPositions: List<String>,
    val source: PositionSource,
    /** tradeDate 当天产生、用于下一交易日开盘买入的选股结果；允许为空。 */
    val nextSessionSelections: List<String> = emptyList(),
    /** 带模型评分的下一交易日选股结果，按 modelScore 降序排列。 */
    val nextSessionSelectionDetails: List<StrategySelectionSnapshot> = emptyList(),
    /** 下一交易日候选中相对当前持仓新进入的股票；用于调入提示，不代表完整策略选股列表。 */
    val newlySelected: List<String> = emptyList(),
)

// ==================== Candle 与 CandleData 转换扩展函数 ====================

/**
 * 将 CandleData 转换为 Candle（用于客户端接收数据后重建完整模型）
 * 注意：部分字段（如估值指标）无法从 CandleData 恢复，将使用默认值
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun CandleData.toCandle(tsCode: String): model.Candle =
    model.Candle(
        tsCode = tsCode,
        date = kotlinx.datetime.LocalDate.parse(date.substringBefore(" ").take(10)),
        open = open,
        high = high,
        low = low,
        close = close,
        adj = adjClose ?: close,
        openQfq = adjOpen ?: 0f,
        closeQfq = adjClose ?: 0f,
        highQfq = adjHigh ?: 0f,
        lowQfq = adjLow ?: 0f,
        volume = volume,
        volumeQfq = 0f,
        turnoverReal = turnover,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )

/**
 * 将 Candle 转换为 CandleData（用于 WebSocket 传输，减少网络负载）
 * @param useAdjusted 是否优先使用前复权数据
 */
fun model.Candle.toCandleData(useAdjusted: Boolean = true): CandleData =
    if (useAdjusted && closeQfq > 0) {
        // 使用前复权数据作为主数据，原始数据作为备选
        CandleData(
            date = date.toString(),
            open = openQfq,
            high = highQfq,
            low = lowQfq,
            close = closeQfq,
            volume = if (volumeQfq > 0) volumeQfq else volume,
            turnover = turnoverReal,
            changePercent = null,  // WebSocket传输时由客户端计算
            amplitude = null,
            adjOpen = open,
            adjHigh = high,
            adjLow = low,
            adjClose = close
        )
    } else {
        // 使用原始数据，复权数据作为可选字段
        CandleData(
            date = date.toString(),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            turnover = turnoverReal,
            changePercent = null,
            amplitude = null,
            adjOpen = if (openQfq > 0) openQfq else null,
            adjHigh = if (highQfq > 0) highQfq else null,
            adjLow = if (lowQfq > 0) lowQfq else null,
            adjClose = if (closeQfq > 0) closeQfq else null
        )
    }

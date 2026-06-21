package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingEdge
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingExitReason
import model.candle.StrategyTrackingSection
import org.shiroumi.quant_kmp.ui.utils.formatPriceRaw

// 6 = 每日入场 3 只 × H3（持仓存活 daysSinceEntry 0/1，第 2 日离场）→ 同一交易日并发在手最多 6 只。
// selection 仍是 Top5（≤6）、cleared 一日离场亦 ≤6，统一用 6 覆盖三列上限，避免第 6 只持仓被静默截断。
internal const val TrackingSlotCount = 6
internal const val TrackingRealtimeDayLabel = "今天"

/**
 * 最早可校准跟随起始日的下界（业务硬约束，与后端一致）。
 * tradeDate 为 ISO `yyyy-MM-dd`，字典序等于日期序，可直接字符串比较。
 */
internal const val CalibrationMinTradeDate = "2026-05-18"

/**
 * 持仓跟踪时间线。节点盈亏、离场判决与流转边盈亏全部由 strategy-service 云端计算
 * （`StrategyPositionTrackingRuntime`），前端只做槽位裁剪与渲染。
 * [followStartDate] 非空表示最早跟随日校准视图：持仓/清仓列为该日空仓起步的重放结果。
 */
data class StrategyPositionTrackingTimeline(
    val days: List<StrategyPositionTrackingDay>,
    val edges: List<StrategyTrackingEdge>,
    val realtimeTradeDate: String? = null,
    val followStartDate: String? = null,
)

internal fun trackingCardKey(
    tradeDate: String,
    section: StrategyTrackingSection,
    stockCode: String,
    slotIndex: Int,
): String = "tracking-card-$tradeDate-${section.name.lowercase()}-$stockCode-$slotIndex"

internal fun trackingStockNameKey(cardKey: String): String = "$cardKey/name"
internal fun trackingStockCodeKey(cardKey: String): String = "$cardKey/code"

fun StrategyPositionTrackingResponse.toTimeline(): StrategyPositionTrackingTimeline {
    val normalizedDays = days.map { it.limitSlots() }
    return StrategyPositionTrackingTimeline(
        days = normalizedDays,
        edges = edges.filter { it.fromSlotIndex < TrackingSlotCount && it.toSlotIndex < TrackingSlotCount },
        realtimeTradeDate = realtimeTradeDate,
        followStartDate = followStartDate,
    )
}

private fun StrategyPositionTrackingDay.limitSlots(): StrategyPositionTrackingDay = copy(
    selection = selection.sortedBy { it.slotIndex }.take(TrackingSlotCount),
    holdings = holdings.sortedBy { it.slotIndex }.take(TrackingSlotCount),
    cleared = cleared.sortedBy { it.slotIndex }.take(TrackingSlotCount),
)

internal fun StrategyPositionTrackingTimeline.isRealtimeDay(day: StrategyPositionTrackingDay): Boolean =
    realtimeTradeDate != null && day.tradeDate == realtimeTradeDate

/**
 * 校准可选交易日：完整审计窗口去掉盘中实时投影日与早于下界的日子，最新在上。
 *
 * 审计窗口的 [days] 集合不随校准激活收缩，是稳定数据源；盘中实时投影日不在后端
 * 已确认交易日窗口内（校准会被拒），必须排除；早于 [CalibrationMinTradeDate] 的日子
 * 同样不可校准。倒序使最新交易日置顶，贴合用户多选近几日的习惯。
 */
internal fun StrategyPositionTrackingTimeline.calibratableTradeDates(): List<String> =
    days.filterNot { isRealtimeDay(it) }
        .map { it.tradeDate }
        .filter { it >= CalibrationMinTradeDate }
        .asReversed()

/** 离场原因展示文案。 */
internal fun StrategyTrackingExitReason.label(): String = when (this) {
    StrategyTrackingExitReason.TAKE_PROFIT -> "止盈"
    StrategyTrackingExitReason.PROFIT_PROTECT -> "保盈"
    StrategyTrackingExitReason.SHALLOW_STOP -> "止损"
    StrategyTrackingExitReason.TIME_STOP -> "到期"
    StrategyTrackingExitReason.PRICE_STOP -> "破位止损"
}

/** 流转边标签数据：文本 + 涨跌方向（null = 中性，无盈亏数值）。 */
internal data class TrackingEdgeLabelData(
    val text: String,
    val positive: Boolean?,
)

/**
 * 流转边 → Canvas 标签。盈亏数值来自云端：
 * 持有边 = 目标日当日涨跌；买入边 = 入场日开盘→收盘；卖出边 = 规则口径已实现收益 + 离场原因。
 */
internal fun StrategyTrackingEdge.toLabelData(): TrackingEdgeLabelData? {
    val pnlText = pnlPct?.let(::formatPnlPercent)
    val text = when (kind) {
        StrategyTrackingEdgeKind.HOLD_CONTINUE -> pnlText
        StrategyTrackingEdgeKind.ENTER_HOLDING -> pnlText?.let { "买入 $it" }
        StrategyTrackingEdgeKind.EXIT_CLEAR -> {
            val reason = exitReason?.label()
            when {
                reason != null && pnlText != null -> "$reason $pnlText"
                pnlText != null -> "卖出 $pnlText"
                else -> reason
            }
        }
    } ?: return null
    return TrackingEdgeLabelData(text = text, positive = pnlPct?.let { it >= 0f })
}

/** 边的业务键：用于把布局结果（索引化的边）回连到云端边数据。 */
internal fun StrategyTrackingEdge.lookupKey(): String =
    "$fromDate|$fromSection|$fromSlotIndex|$toDate|$toSection|$toSlotIndex|$kind"

internal fun formatPnlPercent(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100f
    val sign = if (rounded >= 0) "+" else "-"
    val absVal = kotlin.math.abs(rounded)
    val intPart = absVal.toInt()
    val decimal = kotlin.math.round((absVal - intPart) * 100).toInt()
    val decimalStr = if (decimal < 10) "0$decimal" else "$decimal"
    return "$sign$intPart.$decimalStr%"
}

/** 跟踪页价格两位小数格式化：统一复用 [formatPriceRaw]，列表 / 全景图 / 详情卡共用。 */
internal fun formatTrackingPrice(value: Float): String = formatPriceRaw(value)

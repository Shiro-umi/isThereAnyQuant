package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlinx.serialization.Serializable
import model.Candle
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import kotlinx.datetime.LocalDate

internal const val TrackingSlotCount = 5
internal const val TrackingRealtimeDayLabel = "今天"

@Serializable
enum class TrackingEdgeKind {
    HOLD_CONTINUE,
    ENTER_HOLDING,
    EXIT_CLEAR,
}

data class TrackingEdge(
    val fromDate: String,
    val fromSection: StrategyTrackingSection,
    val fromStockCode: String,
    val fromSlotIndex: Int,
    val toDate: String,
    val toSection: StrategyTrackingSection,
    val toStockCode: String,
    val toSlotIndex: Int,
    val kind: TrackingEdgeKind,
)

data class StrategyPositionTrackingTimeline(
    val days: List<StrategyPositionTrackingDay>,
    val edges: List<TrackingEdge>,
    val realtimeTradeDate: String? = null,
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
    return buildTimelineFromDays(normalizedDays)
}

internal fun buildTimelineFromDays(
    days: List<StrategyPositionTrackingDay>,
    realtimeTradeDate: String? = null,
): StrategyPositionTrackingTimeline {
    val normalizedDays = days.map { it.limitSlots() }
    val edges = buildList {
        normalizedDays.zipWithNext { current, next ->
            addAll(buildHoldContinueEdges(current, next))
            addAll(buildExitClearEdges(current, next))
        }
        // 入场连线：多日持有下，新进持仓可能源于 1~N 天前的选股。
        // 按持仓 buyDate（=入场日）追溯到「买入日的前一交易日」那一天的选股节点连线。
        addAll(buildEnterHoldingEdges(normalizedDays))
    }
    return StrategyPositionTrackingTimeline(
        days = normalizedDays,
        edges = edges,
        realtimeTradeDate = realtimeTradeDate,
    )
}

private fun StrategyPositionTrackingDay.limitSlots(): StrategyPositionTrackingDay = copy(
    selection = selection.sortedBy { it.slotIndex }.take(TrackingSlotCount),
    holdings = holdings.sortedBy { it.slotIndex }.take(TrackingSlotCount),
    cleared = cleared.sortedBy { it.slotIndex }.take(TrackingSlotCount),
)

private fun buildHoldContinueEdges(
    current: StrategyPositionTrackingDay,
    next: StrategyPositionTrackingDay,
): List<TrackingEdge> {
    val nextHoldings = next.holdings.associateBy { it.stockCode }
    return current.holdings.mapNotNull { from ->
        nextHoldings[from.stockCode]?.toEdge(
            from = from,
            currentDate = current.tradeDate,
            nextDate = next.tradeDate,
            kind = TrackingEdgeKind.HOLD_CONTINUE,
        )
    }
}

private fun buildExitClearEdges(
    current: StrategyPositionTrackingDay,
    next: StrategyPositionTrackingDay,
): List<TrackingEdge> {
    val nextCleared = next.cleared.associateBy { it.stockCode }
    return current.holdings.mapNotNull { from ->
        nextCleared[from.stockCode]?.toEdge(
            from = from,
            currentDate = current.tradeDate,
            nextDate = next.tradeDate,
            kind = TrackingEdgeKind.EXIT_CLEAR,
        )
    }
}

/**
 * 入场连线（跨多日追溯）。
 *
 * 多日持有 + 止盈止损语义下，一只持仓票的入场日 [StrategyTrackingStockNode.buyDate] 可能早于昨天。
 * 它在「入场日的前一交易日」被选出（今天选、次日买入）。本函数对每一天的持仓，
 * 找出当天「新入场」的票（buyDate == 当天 tradeDate，且前一交易日未持有），
 * 连回前一交易日的选股节点。每只票只在入场当日画一条入线，避免重复。
 */
private fun buildEnterHoldingEdges(
    days: List<StrategyPositionTrackingDay>,
): List<TrackingEdge> {
    if (days.size < 2) return emptyList()
    return buildList {
        for (index in 1 until days.size) {
            val buyDay = days[index]
            val selectionDay = days[index - 1]
            val previousHoldingCodes = selectionDay.holdings.mapTo(mutableSetOf()) { it.stockCode }
            val selectionByCode = selectionDay.selection.associateBy { it.stockCode }
            for (holding in buyDay.holdings) {
                // 仅对「入场日 == 当天 且 前一交易日未持有」的新进持仓追溯入线
                if (holding.buyDate != buyDay.tradeDate) continue
                if (holding.stockCode in previousHoldingCodes) continue
                val from = selectionByCode[holding.stockCode] ?: continue
                add(
                    holding.toEdge(
                        from = from,
                        currentDate = selectionDay.tradeDate,
                        nextDate = buyDay.tradeDate,
                        kind = TrackingEdgeKind.ENTER_HOLDING,
                    )
                )
            }
        }
    }
}

private fun StrategyTrackingStockNode.toEdge(
    from: StrategyTrackingStockNode,
    currentDate: String,
    nextDate: String,
    kind: TrackingEdgeKind,
): TrackingEdge = TrackingEdge(
    fromDate = currentDate,
    fromSection = from.section,
    fromStockCode = from.stockCode,
    fromSlotIndex = from.slotIndex,
    toDate = nextDate,
    toSection = section,
    toStockCode = stockCode,
    toSlotIndex = slotIndex,
    kind = kind,
)

internal fun StrategyPositionTrackingTimeline.isRealtimeDay(day: StrategyPositionTrackingDay): Boolean =
    realtimeTradeDate != null && day.tradeDate == realtimeTradeDate

internal fun buildTrackingCycleBuyDates(
    days: List<StrategyPositionTrackingDay>,
): Map<String, Map<LocalDate, LocalDate>> {
    if (days.isEmpty()) return emptyMap()

    val activeBuyDates = mutableMapOf<String, LocalDate>()
    val buyDatesByCode = mutableMapOf<String, MutableMap<LocalDate, LocalDate>>()
    var previousHoldingCodes = emptySet<String>()

    val firstDay = days.first()
    firstDay.holdings.forEach { node ->
        val fallback = LocalDate.parse(firstDay.tradeDate)
        activeBuyDates[node.stockCode] = node.buyDate?.let { LocalDate.parse(it) } ?: fallback
    }

    days.forEach { day ->
        val tradeDate = LocalDate.parse(day.tradeDate)
        val holdingCodes = day.holdings.map { it.stockCode }.toSet()
        val enteredHoldingCodes = holdingCodes - previousHoldingCodes

        enteredHoldingCodes.forEach { stockCode ->
            if (stockCode !in activeBuyDates) {
                val node = day.holdings.firstOrNull { it.stockCode == stockCode }
                activeBuyDates[stockCode] = node?.buyDate?.let { LocalDate.parse(it) } ?: tradeDate
            }
        }

        val observedNodes = day.holdings + day.cleared
        observedNodes.forEach { node ->
            val buyDate = activeBuyDates[node.stockCode] ?: return@forEach
            buyDatesByCode
                .getOrPut(node.stockCode) { mutableMapOf() }[tradeDate] = buyDate
        }

        activeBuyDates.keys.retainAll(holdingCodes)
        previousHoldingCodes = holdingCodes
    }

    return buyDatesByCode
}

internal fun fillRealtimePnl(
    node: StrategyTrackingStockNode,
    observationDate: LocalDate,
    buyDate: LocalDate?,
    candlesByCode: Map<String, List<Candle>>,
): StrategyTrackingStockNode {
    if (buyDate == null) return node
    val candles = candlesByCode[node.stockCode].orEmpty()
    if (candles.isEmpty()) return node

    val candlesByDate = candles.associateBy { it.date }
    val buyCandle = candlesByDate[buyDate]
        ?: candles.firstOrNull { it.date >= buyDate }
        ?: return node
    val currentCandle = candlesByDate[observationDate] ?: candles.lastOrNull() ?: return node
    val buyPrice = buyCandle.open.takeIf { it > 0f } ?: buyCandle.openQfq.takeIf { it > 0f } ?: return node
    val currentPrice = currentCandle.close
    val actualPnl = ((currentPrice - buyPrice) / buyPrice) * 100f
    val maxHigh = candles
        .filter { candle -> candle.date >= buyDate && candle.date <= observationDate }
        .maxOfOrNull { it.high }
        ?: currentCandle.high
    val maxPnl = ((maxHigh - buyPrice) / buyPrice) * 100f

    return node.copy(
        buyDate = buyDate.toString(),
        buyPrice = buyPrice,
        actualPnl = actualPnl,
        maxPnl = maxPnl,
    )
}

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
            addAll(buildEnterHoldingEdges(current, next))
        }
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

private fun buildEnterHoldingEdges(
    current: StrategyPositionTrackingDay,
    next: StrategyPositionTrackingDay,
): List<TrackingEdge> {
    val nextHoldings = next.holdings.associateBy { it.stockCode }
    return current.selection.mapNotNull { from ->
        nextHoldings[from.stockCode]?.toEdge(
            from = from,
            currentDate = current.tradeDate,
            nextDate = next.tradeDate,
            kind = TrackingEdgeKind.ENTER_HOLDING,
        )
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

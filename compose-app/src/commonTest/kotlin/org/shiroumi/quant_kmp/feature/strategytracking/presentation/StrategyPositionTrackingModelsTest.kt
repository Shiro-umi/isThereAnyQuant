package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingEdge
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingExitReason
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode

class StrategyPositionTrackingModelsTest {

    @Test
    fun toTimelinePassesThroughCloudEdgesAndRealtimeFlag() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(
                    tradeDate = "2026-04-14",
                    holdings = listOf(node("600519.SH", "贵州茅台", StrategyTrackingSection.HOLDINGS, 0)),
                ),
                day(
                    tradeDate = "2026-04-15",
                    holdings = listOf(node("600519.SH", "贵州茅台", StrategyTrackingSection.HOLDINGS, 0)),
                ),
            ),
            edges = listOf(
                edge(
                    fromDate = "2026-04-14",
                    toDate = "2026-04-15",
                    kind = StrategyTrackingEdgeKind.HOLD_CONTINUE,
                    pnlPct = 1.25f,
                ),
            ),
            realtimeTradeDate = "2026-04-15",
        ).toTimeline()

        assertEquals(1, timeline.edges.size)
        assertEquals(1.25f, timeline.edges.single().pnlPct)
        assertEquals("2026-04-15", timeline.realtimeTradeDate)
        assertEquals(true, timeline.isRealtimeDay(timeline.days.last()))
        assertEquals(false, timeline.isRealtimeDay(timeline.days.first()))
    }

    @Test
    fun toTimelineDropsEdgesBeyondSlotLimit() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(day(tradeDate = "2026-04-14"), day(tradeDate = "2026-04-15")),
            edges = listOf(
                edge("2026-04-14", "2026-04-15", StrategyTrackingEdgeKind.HOLD_CONTINUE, toSlotIndex = TrackingSlotCount),
                edge("2026-04-14", "2026-04-15", StrategyTrackingEdgeKind.HOLD_CONTINUE, toSlotIndex = 0),
            ),
        ).toTimeline()

        assertEquals(1, timeline.edges.size)
        assertEquals(0, timeline.edges.single().toSlotIndex)
    }

    @Test
    fun keepsTimelineStableForEmptyDays() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(tradeDate = "2026-04-18"),
                day(tradeDate = "2026-04-21"),
            )
        ).toTimeline()

        assertEquals(2, timeline.days.size)
        assertEquals(0, timeline.edges.size)
        assertEquals(null, timeline.realtimeTradeDate)
    }

    @Test
    fun exitEdgeLabelCombinesReasonAndRealizedPnl() {
        val label = edge(
            fromDate = "2026-04-14",
            toDate = "2026-04-15",
            kind = StrategyTrackingEdgeKind.EXIT_CLEAR,
            pnlPct = 7.0f,
            exitReason = StrategyTrackingExitReason.TAKE_PROFIT,
        ).toLabelData()

        assertEquals("止盈 +7.00%", label?.text)
        assertEquals(true, label?.positive)
    }

    @Test
    fun holdEdgeLabelShowsDailyChangeOnly() {
        val label = edge(
            fromDate = "2026-04-14",
            toDate = "2026-04-15",
            kind = StrategyTrackingEdgeKind.HOLD_CONTINUE,
            pnlPct = -2.5f,
        ).toLabelData()

        assertEquals("-2.50%", label?.text)
        assertEquals(false, label?.positive)
    }

    @Test
    fun enterEdgeWithoutPnlHasNoLabel() {
        val label = edge(
            fromDate = "2026-04-14",
            toDate = "2026-04-15",
            kind = StrategyTrackingEdgeKind.ENTER_HOLDING,
        ).toLabelData()

        assertEquals(null, label)
    }

    @Test
    fun calibratableTradeDatesExcludesRealtimeAndBelowFloorThenReversed() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(tradeDate = "2026-05-15"), // 早于下界，剔除
                day(tradeDate = "2026-05-18"), // 下界当日，保留
                day(tradeDate = "2026-05-19"),
                day(tradeDate = "2026-05-20"), // 末日为盘中实时投影日，剔除
            ),
            realtimeTradeDate = "2026-05-20",
        ).toTimeline()

        // 已确认且不早于下界的交易日，最新在上
        assertEquals(listOf("2026-05-19", "2026-05-18"), timeline.calibratableTradeDates())
    }

    @Test
    fun calibratableTradeDatesKeepsAllConfirmedDaysWhenNoRealtime() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(tradeDate = "2026-05-18"),
                day(tradeDate = "2026-05-19"),
                day(tradeDate = "2026-05-20"),
            ),
        ).toTimeline()

        assertEquals(listOf("2026-05-20", "2026-05-19", "2026-05-18"), timeline.calibratableTradeDates())
    }

    private fun day(
        tradeDate: String,
        selection: List<StrategyTrackingStockNode> = emptyList(),
        holdings: List<StrategyTrackingStockNode> = emptyList(),
        cleared: List<StrategyTrackingStockNode> = emptyList(),
    ) = StrategyPositionTrackingDay(
        tradeDate = tradeDate,
        selection = selection,
        holdings = holdings,
        cleared = cleared,
    )

    private fun node(
        code: String,
        name: String,
        section: StrategyTrackingSection,
        slotIndex: Int,
    ) = StrategyTrackingStockNode(
        stockCode = code,
        stockName = name,
        section = section,
        slotIndex = slotIndex,
    )

    private fun edge(
        fromDate: String,
        toDate: String,
        kind: StrategyTrackingEdgeKind,
        pnlPct: Float? = null,
        exitReason: StrategyTrackingExitReason? = null,
        toSlotIndex: Int = 0,
    ) = StrategyTrackingEdge(
        fromDate = fromDate,
        fromSection = StrategyTrackingSection.HOLDINGS,
        fromStockCode = "600519.SH",
        fromSlotIndex = 0,
        toDate = toDate,
        toSection = StrategyTrackingSection.HOLDINGS,
        toStockCode = "600519.SH",
        toSlotIndex = toSlotIndex,
        kind = kind,
        pnlPct = pnlPct,
        exitReason = exitReason,
    )
}

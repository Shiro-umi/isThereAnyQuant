package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode

class StrategyPositionTrackingModelsTest {

    @Test
    fun buildsHoldContinueEnterAndExitEdgesFromAdjacentDays() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(
                    tradeDate = "2026-04-14",
                    selection = listOf(node("000001.SZ", "平安银行", StrategyTrackingSection.SELECTION, 0)),
                    holdings = listOf(
                        node("600519.SH", "贵州茅台", StrategyTrackingSection.HOLDINGS, 0),
                        node("000333.SZ", "美的集团", StrategyTrackingSection.HOLDINGS, 1),
                    ),
                ),
                day(
                    tradeDate = "2026-04-15",
                    holdings = listOf(
                        node("600519.SH", "贵州茅台", StrategyTrackingSection.HOLDINGS, 0),
                        node("000001.SZ", "平安银行", StrategyTrackingSection.HOLDINGS, 1),
                    ),
                    cleared = listOf(node("000333.SZ", "美的集团", StrategyTrackingSection.CLEARED, 0)),
                ),
            )
        ).toTimeline()

        assertEquals(3, timeline.edges.size)
        assertEquals(1, timeline.edges.count { it.kind == TrackingEdgeKind.HOLD_CONTINUE })
        assertEquals(1, timeline.edges.count { it.kind == TrackingEdgeKind.ENTER_HOLDING })
        assertEquals(1, timeline.edges.count { it.kind == TrackingEdgeKind.EXIT_CLEAR })
    }

    @Test
    fun skipsEnterEdgeWhenSelectionDoesNotBecomeHoldingNextDay() {
        val timeline = StrategyPositionTrackingResponse(
            days = listOf(
                day(
                    tradeDate = "2026-04-16",
                    selection = listOf(node("300750.SZ", "宁德时代", StrategyTrackingSection.SELECTION, 0)),
                ),
                day(
                    tradeDate = "2026-04-17",
                    holdings = emptyList(),
                ),
            )
        ).toTimeline()

        assertEquals(0, timeline.edges.size)
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
}

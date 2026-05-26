package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import model.candle.StrategyTrackingSection

class TrackingComputeRuntimeTest {

    @Test
    fun buildTrackingEdgeLayoutSync_indexesEdgesByTradeDate() {
        val result = buildTrackingEdgeLayoutSync(
            TrackingEdgeLayoutInput(
                tradeDates = listOf("2026-04-14", "2026-04-15", "2026-04-16"),
                edges = listOf(
                    TrackingEdgeTaskPayload(
                        fromDate = "2026-04-14",
                        fromSection = StrategyTrackingSection.SELECTION,
                        fromSlotIndex = 0,
                        toDate = "2026-04-15",
                        toSection = StrategyTrackingSection.HOLDINGS,
                        toSlotIndex = 1,
                        kind = TrackingEdgeKind.ENTER_HOLDING,
                    ),
                    TrackingEdgeTaskPayload(
                        fromDate = "2026-04-15",
                        fromSection = StrategyTrackingSection.HOLDINGS,
                        fromSlotIndex = 1,
                        toDate = "2026-04-16",
                        toSection = StrategyTrackingSection.CLEARED,
                        toSlotIndex = 2,
                        kind = TrackingEdgeKind.EXIT_CLEAR,
                    ),
                ),
            ),
        )

        assertEquals(2, result.indexedEdges.size)
        assertEquals(0, result.indexedEdges[0].fromIndex)
        assertEquals(1, result.indexedEdges[0].toIndex)
        assertEquals(1, result.indexedEdges[1].fromIndex)
        assertEquals(2, result.indexedEdges[1].toIndex)
    }

    @Test
    fun buildTrackingEdgeLayoutSync_skipsEdgesWhoseDatesAreMissing() {
        val result = buildTrackingEdgeLayoutSync(
            TrackingEdgeLayoutInput(
                tradeDates = listOf("2026-04-15"),
                edges = listOf(
                    TrackingEdgeTaskPayload(
                        fromDate = "2026-04-14",
                        fromSection = StrategyTrackingSection.SELECTION,
                        fromSlotIndex = 0,
                        toDate = "2026-04-15",
                        toSection = StrategyTrackingSection.HOLDINGS,
                        toSlotIndex = 0,
                        kind = TrackingEdgeKind.ENTER_HOLDING,
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), result.indexedEdges)
    }
}

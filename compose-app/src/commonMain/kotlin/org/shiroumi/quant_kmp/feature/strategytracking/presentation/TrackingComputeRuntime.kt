package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlinx.serialization.Serializable
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingSection

internal interface PlatformComputeRuntime {
    suspend fun buildTrackingEdgeLayout(input: TrackingEdgeLayoutInput): TrackingEdgeLayoutResult
}

internal expect fun platformComputeRuntime(): PlatformComputeRuntime

@Serializable
internal data class TrackingEdgeTaskPayload(
    val fromDate: String,
    val fromSection: StrategyTrackingSection,
    val fromSlotIndex: Int,
    val toDate: String,
    val toSection: StrategyTrackingSection,
    val toSlotIndex: Int,
    val kind: StrategyTrackingEdgeKind,
)

@Serializable
internal data class TrackingEdgeLayoutInput(
    val tradeDates: List<String>,
    val edges: List<TrackingEdgeTaskPayload>,
)

@Serializable
internal data class IndexedTrackingEdgePayload(
    val kind: StrategyTrackingEdgeKind,
    val fromSection: StrategyTrackingSection,
    val fromSlotIndex: Int,
    val toSection: StrategyTrackingSection,
    val toSlotIndex: Int,
    val fromIndex: Int,
    val toIndex: Int,
)

@Serializable
internal data class TrackingEdgeLayoutResult(
    val indexedEdges: List<IndexedTrackingEdgePayload> = emptyList(),
)

internal fun StrategyPositionTrackingTimeline.toTrackingEdgeLayoutInput(): TrackingEdgeLayoutInput =
    TrackingEdgeLayoutInput(
        tradeDates = days.map { it.tradeDate },
        edges = edges.map { edge ->
            TrackingEdgeTaskPayload(
                fromDate = edge.fromDate,
                fromSection = edge.fromSection,
                fromSlotIndex = edge.fromSlotIndex,
                toDate = edge.toDate,
                toSection = edge.toSection,
                toSlotIndex = edge.toSlotIndex,
                kind = edge.kind,
            )
        },
    )

internal fun buildTrackingEdgeLayoutSync(input: TrackingEdgeLayoutInput): TrackingEdgeLayoutResult {
    val dayIndexByDate = input.tradeDates.withIndex().associate { (index, tradeDate) -> tradeDate to index }
    val indexedEdges = input.edges.mapNotNull { edge ->
        val fromIndex = dayIndexByDate[edge.fromDate] ?: return@mapNotNull null
        val toIndex = dayIndexByDate[edge.toDate] ?: return@mapNotNull null
        IndexedTrackingEdgePayload(
            kind = edge.kind,
            fromSection = edge.fromSection,
            fromSlotIndex = edge.fromSlotIndex,
            toSection = edge.toSection,
            toSlotIndex = edge.toSlotIndex,
            fromIndex = fromIndex,
            toIndex = toIndex,
        )
    }
    return TrackingEdgeLayoutResult(indexedEdges = indexedEdges)
}

package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import model.candle.StrategyTrackingSection
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.AdaptiveLayoutConfig

internal enum class TrackingTextScale {
    COMPACT,
    REGULAR,
    LARGE,
}

internal data class TimelineLayoutSpec(
    val columnWidth: Dp,
    val columnGap: Dp,
    val viewportTopInset: Dp,
    val viewportBottomInset: Dp,
    val cardStartInset: Dp,
    val cardEndPadding: Dp,
    val cardTopPadding: Dp,
    val cardBottomPadding: Dp,
    val dateBlockHeight: Dp,
    val dateBottomGap: Dp,
    val sectionHeaderHeight: Dp,
    val slotHeight: Dp,
    val slotGap: Dp,
    val sectionGap: Dp,
    val edgeAnchorInset: Dp,
    val sideFadeWidth: Dp,
    val headerTextScale: TrackingTextScale,
    val dateTextScale: TrackingTextScale,
    val sectionTitleTextScale: TrackingTextScale,
    val bodyTextScale: TrackingTextScale,
    val codeTextScale: TrackingTextScale,
) {
    val sectionHeight: Dp = sectionHeaderHeight + (slotHeight * TrackingSlotCount) + (slotGap * (TrackingSlotCount - 1))
    val contentHeight: Dp =
        dateBlockHeight +
            dateBottomGap +
            (sectionHeight * 3) +
            (sectionGap * 2)
    val minimumCardHeight: Dp = cardTopPadding + contentHeight + cardBottomPadding
}

internal data class TimelineGeometry(
    val spec: TimelineLayoutSpec,
    val viewportWidth: Dp,
    val viewportHeight: Dp,
    val edgeContentPadding: Dp,
    val lazyRowTop: Dp,
    val lazyRowBottom: Dp,
    val cardHeight: Dp,
    private val slotCenterYBySection: Map<StrategyTrackingSection, List<Dp>>,
) {
    val columnWidth: Dp get() = spec.columnWidth
    val columnGap: Dp get() = spec.columnGap
    val viewportTopInset: Dp get() = spec.viewportTopInset
    val viewportBottomInset: Dp get() = spec.viewportBottomInset
    val dateBlockHeight: Dp get() = spec.dateBlockHeight
    val dateBottomGap: Dp get() = spec.dateBottomGap
    val sectionHeaderHeight: Dp get() = spec.sectionHeaderHeight
    val slotHeight: Dp get() = spec.slotHeight
    val slotGap: Dp get() = spec.slotGap
    val sectionGap: Dp get() = spec.sectionGap
    val sideFadeWidth: Dp get() = spec.sideFadeWidth
    val cardStartInset: Dp get() = spec.cardStartInset
    val cardEndPadding: Dp get() = spec.cardEndPadding
    val cardTopPadding: Dp get() = spec.cardTopPadding
    val cardBottomPadding: Dp get() = spec.cardBottomPadding
    val headerTextScale: TrackingTextScale get() = spec.headerTextScale
    val dateTextScale: TrackingTextScale get() = spec.dateTextScale
    val sectionTitleTextScale: TrackingTextScale get() = spec.sectionTitleTextScale
    val bodyTextScale: TrackingTextScale get() = spec.bodyTextScale
    val codeTextScale: TrackingTextScale get() = spec.codeTextScale

    val outboundAnchorX: Dp = columnWidth - cardEndPadding
    val inboundAnchorX: Dp = cardStartInset

    fun slotCenterY(section: StrategyTrackingSection, slotIndex: Int): Dp =
        slotCenterYBySection.getValue(section)[slotIndex]
}

internal fun trackingTimelineLayoutSpec(
    config: AdaptiveLayoutConfig,
): TimelineLayoutSpec = TimelineLayoutSpec(
    columnWidth = when {
        config.isCompact -> 224.dp
        config.isMedium -> 264.dp
        else -> 292.dp
    },
    columnGap = if (config.isCompact) 72.dp else 112.dp,
    viewportTopInset = 20.dp,
    viewportBottomInset = 40.dp,
    cardStartInset = 20.dp,
    cardEndPadding = 20.dp,
    cardTopPadding = 12.dp,
    cardBottomPadding = 16.dp,
    dateBlockHeight = 72.dp,
    dateBottomGap = 16.dp,
    sectionHeaderHeight = 32.dp,
    slotHeight = 48.dp,
    slotGap = 3.dp,
    sectionGap = 40.dp,
    edgeAnchorInset = 10.dp,
    sideFadeWidth = 72.dp,
    headerTextScale = if (config.isCompact) TrackingTextScale.REGULAR else TrackingTextScale.LARGE,
    dateTextScale = if (config.isCompact) TrackingTextScale.COMPACT else TrackingTextScale.REGULAR,
    sectionTitleTextScale = TrackingTextScale.REGULAR,
    bodyTextScale = TrackingTextScale.REGULAR,
    codeTextScale = TrackingTextScale.COMPACT,
)

internal fun timelineGeometry(
    spec: TimelineLayoutSpec,
    viewportWidth: Dp,
    viewportHeight: Dp,
): TimelineGeometry {
    val edgeContentPadding = maxOf(4.dp, (viewportWidth - spec.columnWidth) / 2)
    val cardHeight = spec.minimumCardHeight
    val availableHeight = viewportHeight - spec.viewportTopInset - spec.viewportBottomInset
    val extraCentering = maxOf(0.dp, (availableHeight - cardHeight) / 2)
    val lazyRowTop = spec.viewportTopInset + extraCentering
    val lazyRowBottom = spec.viewportBottomInset + extraCentering
    val cardContentTop = lazyRowTop + spec.cardTopPadding
    val dateBottom = cardContentTop + spec.dateBlockHeight + spec.dateBottomGap

    fun sectionTop(section: StrategyTrackingSection): Dp = dateBottom + when (section) {
        StrategyTrackingSection.SELECTION -> 0.dp
        StrategyTrackingSection.HOLDINGS -> spec.sectionHeight + spec.sectionGap
        StrategyTrackingSection.CLEARED -> (spec.sectionHeight * 2) + (spec.sectionGap * 2)
    }

    val slotCenters = StrategyTrackingSection.entries.associateWith { section ->
        List(TrackingSlotCount) { slotIndex ->
            sectionTop(section) + spec.sectionHeaderHeight +
                (spec.slotHeight * slotIndex) +
                (spec.slotGap * slotIndex) +
                (spec.slotHeight / 2)
        }
    }

    return TimelineGeometry(
        spec = spec,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        edgeContentPadding = edgeContentPadding,
        lazyRowTop = lazyRowTop,
        lazyRowBottom = lazyRowBottom,
        cardHeight = cardHeight,
        slotCenterYBySection = slotCenters,
    )
}

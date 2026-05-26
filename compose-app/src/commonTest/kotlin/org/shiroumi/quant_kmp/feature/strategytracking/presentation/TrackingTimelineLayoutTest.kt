package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import model.candle.StrategyTrackingSection

class TrackingTimelineLayoutTest {

    @Test
    fun computesCardHeightAndCenteredContentOffsetFromViewport() {
        val geometry = timelineGeometry(
            spec = testSpec(),
            viewportWidth = 1200.dp,
            viewportHeight = 1200.dp,
        )

        assertEquals(1064.dp, geometry.cardHeight)
        assertEquals(68.dp, geometry.lazyRowTop)
        assertEquals(470.dp, geometry.edgeContentPadding)
    }

    @Test
    fun computesStableSlotCentersPerSection() {
        val geometry = timelineGeometry(
            spec = testSpec(),
            viewportWidth = 1200.dp,
            viewportHeight = 1200.dp,
        )

        assertEquals(228.dp, geometry.slotCenterY(StrategyTrackingSection.SELECTION, 0))
        assertEquals(608.dp, geometry.slotCenterY(StrategyTrackingSection.HOLDINGS, 1))
        assertEquals(1092.dp, geometry.slotCenterY(StrategyTrackingSection.CLEARED, 4))
    }

    @Test
    fun keepsMinimumCardHeightWhenViewportIsShort() {
        val spec = testSpec()
        val geometry = timelineGeometry(
            spec = spec,
            viewportWidth = 820.dp,
            viewportHeight = 600.dp,
        )

        assertEquals(spec.minimumCardHeight, geometry.cardHeight)
        assertEquals(spec.viewportTopInset, geometry.lazyRowTop)
    }

    private fun testSpec() = TimelineLayoutSpec(
        columnWidth = 260.dp,
        columnGap = 96.dp,
        viewportTopInset = 40.dp,
        viewportBottomInset = 40.dp,
        cardStartInset = 12.dp,
        cardEndPadding = 16.dp,
        cardTopPadding = 16.dp,
        cardBottomPadding = 16.dp,
        dateBlockHeight = 72.dp,
        dateBottomGap = 16.dp,
        sectionHeaderHeight = 32.dp,
        slotHeight = 48.dp,
        slotGap = 4.dp,
        sectionGap = 40.dp,
        edgeAnchorInset = 10.dp,
        sideFadeWidth = 72.dp,
        headerTextScale = TrackingTextScale.LARGE,
        dateTextScale = TrackingTextScale.REGULAR,
        sectionTitleTextScale = TrackingTextScale.REGULAR,
        bodyTextScale = TrackingTextScale.REGULAR,
        codeTextScale = TrackingTextScale.COMPACT,
    )
}

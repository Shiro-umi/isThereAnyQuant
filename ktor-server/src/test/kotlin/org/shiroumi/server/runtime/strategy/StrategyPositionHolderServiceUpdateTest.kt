package org.shiroumi.server.runtime.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot

class StrategyPositionHolderServiceUpdateTest {
    @Test
    fun `service positions snapshot updates holder cache`() {
        val snapshot = StrategyPositionSnapshot(
            tradeDate = "2026-04-30",
            currentPositions = listOf("000001.SZ"),
            source = PositionSource.INTRADAY_REALTIME,
            nextSessionSelections = listOf("000002.SZ"),
            newlySelected = listOf("000002.SZ")
        )

        StrategyPositionHolder.updateFromService(snapshot, reason = "test")

        assertEquals(snapshot, StrategyPositionHolder.snapshot.value)
    }
}

package org.shiroumi.strategy.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntradayRuntimeScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `trading session refreshes during active auction day window`() {
        val schedule = scheduleAt("2026-04-30T02:00:00Z")

        val decision = schedule.currentDecision()

        assertEquals(IntradayRuntimeSchedule.Phase.TRADING_ACTIVE, decision.phase)
        assertTrue(decision.shouldRefresh)
    }

    @Test
    fun `lunch break pauses automatic refresh`() {
        val schedule = scheduleAt("2026-04-30T04:00:00Z")

        val decision = schedule.currentDecision()

        assertEquals(IntradayRuntimeSchedule.Phase.TRADING_BREAK, decision.phase)
        assertFalse(decision.shouldRefresh)
    }

    @Test
    fun `outside session can be explicitly enabled`() {
        val schedule = scheduleAt(
            instant = "2026-04-30T10:00:00Z",
            refreshOutsideTradingSession = true
        )

        val decision = schedule.currentDecision()

        assertEquals(IntradayRuntimeSchedule.Phase.OFF_MARKET, decision.phase)
        assertTrue(decision.shouldRefresh)
    }

    @Test
    fun `non trading day pauses automatic refresh`() {
        val schedule = scheduleAt(
            instant = "2026-05-02T02:00:00Z",
            isTradingDate = false
        )

        val decision = schedule.currentDecision()

        assertEquals(IntradayRuntimeSchedule.Phase.CLOSED_DAY, decision.phase)
        assertFalse(decision.shouldRefresh)
    }

    private fun scheduleAt(
        instant: String,
        refreshOutsideTradingSession: Boolean = false,
        isTradingDate: Boolean = true
    ) = IntradayRuntimeSchedule(
        clock = Clock.fixed(Instant.parse(instant), zone),
        refreshOutsideTradingSession = refreshOutsideTradingSession,
        tradingDateResolver = { _, _ -> isTradingDate }
    )
}

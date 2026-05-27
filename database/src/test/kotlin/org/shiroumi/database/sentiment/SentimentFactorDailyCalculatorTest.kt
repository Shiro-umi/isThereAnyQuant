package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SentimentFactorDailyCalculatorTest {
    @Test
    fun `A group factors use normalized returns weighted by float market value`() {
        val d1 = LocalDate.parse("2026-01-05")
        val d2 = LocalDate.parse("2026-01-06")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d1, "000001.SZ", close = 110.0, previousClose = 100.0, volume = 100.0, previousVolume = 100.0, turnover = 1.0, previousTurnover = 500_000_000.0, mv = 100.0),
                fact(d1, "000002.SZ", close = 90.0, previousClose = 100.0, volume = 400.0, previousVolume = 200.0, turnover = 2.0, previousTurnover = 2_000_000_000.0, mv = 300.0),
                fact(d2, "000001.SZ", close = 121.0, previousClose = 110.0, volume = 200.0, previousVolume = 100.0, turnover = 1.0, previousTurnover = 500_000_000.0, mv = 100.0),
                fact(d2, "000002.SZ", close = 99.0, previousClose = 90.0, volume = 400.0, previousVolume = 400.0, turnover = 2.0, previousTurnover = 2_000_000_000.0, mv = 300.0),
            ),
            limitSummaries = listOf(
                SentimentLimitDailySummary(d1, limitUpClean = 10, limitUpTotal = 10, triggered = 12, limitDown = 2, consecutiveMax = 1, consecutiveCount = 0),
                SentimentLimitDailySummary(d2, limitUpClean = 4, limitUpTotal = 4, triggered = 5, limitDown = 8, consecutiveMax = 1, consecutiveCount = 0),
            ),
            startDate = d1,
            endDate = d2,
        )

        assertEquals(2, records.size)
        val first = records[0].factors
        assertClose(-0.05, first["A1"])
        assertEquals(null, first["A2"])
        assertClose(2.0 / 3.0, first["A3"])
        assertEquals(null, first["A4"])
        assertClose(0.0, first["A5"])
        assertClose(0.0, first["A6"])
        assertClose(0.10, first["A7"])
        assertClose(-0.10, first["A8"])

        val second = records[1].factors
        assertClose(0.10, second["A1"])
        assertClose(0.075, second["A2"])
        assertClose(0.20, second["A3"])
        assertClose(-0.23333333333333334, second["A4"])
        assertClose(-4.0, second["A5"])
        assertClose(4.0, second["A6"])
        assertClose(1.0, second["A10"])
        assertClose(0.0, second["A11"])
        assertClose(1.0, records[1].y2Raw)
    }

    @Test
    fun `C group factors describe limit board ecosystem and y3 raw`() {
        val d1 = LocalDate.parse("2026-01-05")
        val d2 = LocalDate.parse("2026-01-06")
        val d3 = LocalDate.parse("2026-01-07")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d1, "000001.SZ", close = 110.0, previousClose = 100.0, mv = 100.0),
                fact(d1, "000002.SZ", close = 105.0, previousClose = 100.0, mv = 200.0),
                fact(d1, "000003.SZ", close = 95.0, previousClose = 100.0, mv = 300.0),
                fact(d2, "000001.SZ", close = 103.0, previousClose = 100.0, mv = 100.0),
                fact(d2, "000002.SZ", close = 110.0, previousClose = 100.0, mv = 200.0),
                fact(d2, "000003.SZ", close = 97.0, previousClose = 100.0, mv = 300.0),
                fact(d3, "000001.SZ", close = 99.0, previousClose = 100.0, mv = 100.0),
                fact(d3, "000002.SZ", close = 104.0, previousClose = 100.0, mv = 200.0),
                fact(d3, "000003.SZ", close = 110.0, previousClose = 100.0, mv = 300.0),
            ),
            limitSummaries = listOf(
                SentimentLimitDailySummary(
                    tradeDate = d1,
                    limitUpClean = 2,
                    limitUpTotal = 2,
                    triggered = 3,
                    limitDown = 1,
                    consecutiveMax = 1,
                    consecutiveCount = 0,
                    limitUpTsCodes = setOf("000001.SZ", "000002.SZ"),
                ),
                SentimentLimitDailySummary(
                    tradeDate = d2,
                    limitUpClean = 1,
                    limitUpTotal = 1,
                    triggered = 2,
                    limitDown = 1,
                    consecutiveMax = 2,
                    consecutiveCount = 1,
                    limitUpTsCodes = setOf("000002.SZ"),
                    consecutiveTsCodes = setOf("000002.SZ"),
                ),
                SentimentLimitDailySummary(
                    tradeDate = d3,
                    limitUpClean = 2,
                    limitUpTotal = 2,
                    triggered = 4,
                    limitDown = 0,
                    consecutiveMax = 3,
                    consecutiveCount = 2,
                    limitUpTsCodes = setOf("000002.SZ", "000003.SZ"),
                    consecutiveTsCodes = setOf("000002.SZ", "000003.SZ"),
                ),
            ),
            startDate = d1,
            endDate = d3,
        )

        val first = records[0].factors
        assertClose(2.0 / 3.0, first["C1"])
        assertClose(1.0, first["C2"])
        assertClose(1.0, first["C2p"])
        assertClose(0.0, first["C3"])
        assertEquals(null, first["C4"])
        assertEquals(null, first["C6"])
        assertEquals(null, first["C7"])
        assertClose(1.0 / 3.0, records[0].y3Raw)

        val second = records[1].factors
        assertClose(0.5, second["C1"])
        assertClose(2.0, second["C2"])
        assertClose(1.3333333333333335, second["C2p"])
        assertClose(1.0, second["C3"])
        assertClose(0.07666666666666666, second["C4"])
        assertClose(1.0, second["C5"])
        assertClose(0.10, second["C6"])
        assertClose(0.5, second["C7"])
        assertClose(0.0, records[1].y3Raw)

        val third = records[2].factors
        assertClose(0.5, third["C1"])
        assertClose(3.0, third["C2"])
        assertClose(1.888888888888889, third["C2p"])
        assertClose(2.0, third["C3"])
        assertClose(0.023333333333333355, third["C4"])
        assertClose(2.0, third["C5"])
        assertClose(0.076, third["C6"])
        assertClose(0.0, third["C7"])
        assertClose(2.0 / 3.0, records[2].y3Raw)
    }

    @Test
    fun `D group factors describe A1 time-series memory and divergence`() {
        val d1 = LocalDate.parse("2026-01-05")
        val d2 = LocalDate.parse("2026-01-06")
        val d3 = LocalDate.parse("2026-01-07")
        val d4 = LocalDate.parse("2026-01-08")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d1, "000001.SZ", close = 110.0, previousClose = 100.0, volume = 100.0, previousVolume = 100.0),
                fact(d2, "000001.SZ", close = 108.0, previousClose = 100.0, volume = 80.0, previousVolume = 100.0),
                fact(d3, "000001.SZ", close = 106.0, previousClose = 100.0, volume = 70.0, previousVolume = 100.0),
                fact(d4, "000001.SZ", close = 95.0, previousClose = 100.0, volume = 120.0, previousVolume = 100.0),
            ),
            limitSummaries = emptyList(),
            startDate = d1,
            endDate = d4,
        )

        val first = records[0].factors
        assertClose(0.10, first["D1"])
        assertClose(0.10, first["D2"])
        assertClose(0.0, first["D3"])
        assertClose(1.0, first["D4"])
        assertClose(0.0, first["D5"])
        assertClose(1.0, first["D6"])
        assertEquals(null, first["D7"])

        val second = records[1].factors
        assertClose(0.09333333333333334, second["D1"])
        assertClose(0.09636363636363637, second["D2"])
        assertClose(-0.016363636363636372, second["D2"]?.let { records[1].factors["A1"]?.minus(it) })
        assertClose(0.5, second["D4"])
        assertClose(1.0, second["D5"])
        assertClose(2.0, second["D6"])
        assertEquals(null, second["D7"])

        val third = records[2].factors
        assertClose(1.0 / 3.0, third["D4"])
        assertClose(1.0, third["D5"])
        assertClose(3.0, third["D6"])
        assertClose(1.0, third["D7"])

        val fourth = records[3].factors
        assertClose(0.25, fourth["D4"])
        assertClose(1.0, fourth["D5"])
        assertClose(-1.0, fourth["D6"])
        assertClose(0.0, fourth["D7"])
    }

    @Test
    fun `D7 marks symmetric negative momentum decay`() {
        val d1 = LocalDate.parse("2026-01-05")
        val d2 = LocalDate.parse("2026-01-06")
        val d3 = LocalDate.parse("2026-01-07")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d1, "000001.SZ", close = 94.0, previousClose = 100.0),
                fact(d2, "000001.SZ", close = 96.0, previousClose = 100.0),
                fact(d3, "000001.SZ", close = 98.0, previousClose = 100.0),
            ),
            limitSummaries = emptyList(),
            startDate = d1,
            endDate = d3,
        )

        val third = records[2].factors
        assertClose(-1.0, third["D7"])
        assertClose(-3.0, third["D6"])
    }

    @Test
    fun `pct norm applies board caps and ST priority`() {
        val d = LocalDate.parse("2026-01-05")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d, "300001.SZ", close = 120.0, previousClose = 100.0, mv = 100.0),
                fact(d, "688001.SH", close = 120.0, previousClose = 100.0, mv = 100.0),
                fact(d, "830001.BJ", close = 130.0, previousClose = 100.0, mv = 100.0),
                fact(d, "000001.SZ", name = "*ST样本", close = 106.0, previousClose = 100.0, mv = 100.0),
            ),
            limitSummaries = emptyList(),
            startDate = d,
            endDate = d,
        )

        val first = records.single().factors
        assertClose(0.10, first["A1"])
    }

    @Test
    fun `B and E factors describe cross-section breadth distribution and amplitude`() {
        val d1 = LocalDate.parse("2026-01-05")
        val d2 = LocalDate.parse("2026-01-06")
        val d3 = LocalDate.parse("2026-01-07")
        val records = SentimentFactorDailyCalculator.calculate(
            facts = listOf(
                fact(d1, "000001.SZ", close = 110.0, previousClose = 100.0, high = 112.0, low = 98.0),
                fact(d1, "000002.SZ", close = 100.0, previousClose = 100.0, high = 103.0, low = 97.0),
                fact(d1, "000003.SZ", close = 96.0, previousClose = 100.0, high = 101.0, low = 95.0),
                fact(d2, "000001.SZ", close = 106.0, previousClose = 100.0, high = 108.0, low = 99.0),
                fact(d2, "000002.SZ", close = 104.0, previousClose = 100.0, high = 105.0, low = 98.0),
                fact(d2, "000003.SZ", close = 98.0, previousClose = 100.0, high = 102.0, low = 97.0),
                fact(d3, "000001.SZ", close = 94.0, previousClose = 100.0, high = 101.0, low = 93.0),
                fact(d3, "000002.SZ", close = 102.0, previousClose = 100.0, high = 104.0, low = 99.0),
                fact(d3, "000003.SZ", close = 101.0, previousClose = 100.0, high = 103.0, low = 98.0),
            ),
            limitSummaries = emptyList(),
            startDate = d1,
            endDate = d3,
        )

        val first = records[0].factors
        assertClose(1.0 / 3.0, first["B4"])
        assertClose(1.0 / 3.0, first["B5"])
        assertClose(0.0, first["B6"])
        assertEquals(null, first["B7"])
        assertClose(0.08666666666666667, first["E1"])

        val second = records[1].factors
        assertClose(2.0 / 3.0, second["B4"])
        assertClose(1.0 / 3.0, second["B5"])
        assertClose(0.0, second["B6"])
        assertClose(0.07000000000000002, second["E1"])
        assertClose(-0.011111111111111113, second["E2"])

        val third = records[2].factors
        assertClose(2.0 / 3.0, third["B4"])
        assertClose(0.0, third["B5"])
        assertClose(1.0 / 3.0, third["B6"])
        assertClose(1.0 / 6.0, third["B7"])
        assertClose(0.060000000000000005, third["E1"])
        assertClose(-0.01407407407407408, third["E2"])
    }

    private fun fact(
        tradeDate: LocalDate,
        tsCode: String,
        name: String = "样本",
        close: Double,
        previousClose: Double,
        high: Double = close,
        low: Double = close,
        volume: Double = 100.0,
        previousVolume: Double = 100.0,
        turnover: Double = 100.0,
        previousTurnover: Double = 500_000_000.0,
        mv: Double = 100.0,
    ) = SentimentStockDailyFact(
        tradeDate = tradeDate,
        tsCode = tsCode,
        name = name,
        listDate = LocalDate.parse("2020-01-01"),
        delistDate = null,
        closeQfq = close,
        highQfq = high,
        lowQfq = low,
        previousCloseQfq = previousClose,
        volumeQfq = volume,
        previousVolumeQfq = previousVolume,
        turnoverReal = turnover,
        previousTurnoverReal = previousTurnover,
        mvCirc = mv,
    )

    private fun assertClose(expected: Double, actual: Double?) {
        require(actual != null) { "Expected $expected but was null" }
        assertEquals(expected, actual, absoluteTolerance = 1e-9)
    }
}

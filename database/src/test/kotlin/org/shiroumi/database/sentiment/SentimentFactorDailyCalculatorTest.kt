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
                SentimentLimitDailySummary(d1, limitUpClean = 10, limitDown = 2),
                SentimentLimitDailySummary(d2, limitUpClean = 4, limitDown = 8),
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

    private fun fact(
        tradeDate: LocalDate,
        tsCode: String,
        name: String = "样本",
        close: Double,
        previousClose: Double,
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

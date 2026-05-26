package org.shiroumi.backtest.output

import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceReporterTest {

    @Test fun `计算总收益和最大回撤`() {
        val reporter = PerformanceReporter()
        val metrics = reporter.metrics(
            initialCapital = Money.ofYuan(10_000),
            equityCurve = listOf(
                equity(11_000.0),
                equity(10_500.0),
            ),
            fills = emptyList(),
        )

        assertEquals(0.05, metrics.totalReturn)
        assertEquals(0.045455, metrics.maxDrawdown)
    }

    @Test fun `按单 Lot 生命周期统计盈亏贡献和持仓天数`() {
        val reporter = PerformanceReporter()
        val contributions = reporter.lotContributions(
            listOf(
                fill("buy", T0, Side.BUY, price = 10.0),
                fill("sell", T1, Side.SELL, price = 12.0),
            )
        )

        val contribution = contributions.single()
        assertEquals("000001.SZ", contribution.tsCode)
        assertEquals(1, contribution.holdingDays)
        assertTrue(contribution.pnl > Money.ZERO)
    }

    private fun equity(value: Double): EquityPoint = EquityPoint(
        tradeDate = T1,
        cash = Money.ofYuan(value),
        positionValue = Money.ZERO,
        equity = Money.ofYuan(value),
    )

    private fun fill(orderId: String, date: kotlinx.datetime.LocalDate, side: Side, price: Double): Fill {
        val gross = Money.ofTrade(price, 100)
        return Fill(
            orderId = orderId,
            tradeDate = date,
            tsCode = "000001.SZ",
            side = side,
            quantity = 100,
            price = price,
            grossAmount = gross,
            commission = Money.ZERO,
            transferFee = Money.ZERO,
            stampDuty = Money.ZERO,
        )
    }
}

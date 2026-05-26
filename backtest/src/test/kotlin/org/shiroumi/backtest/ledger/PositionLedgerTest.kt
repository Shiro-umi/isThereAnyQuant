package org.shiroumi.backtest.ledger

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PositionLedgerTest {

    @Test fun `applyBuy 创建未结算单 Lot 且 settleDay 在次日释放可卖数量`() {
        val ledger = PositionLedger()
        ledger.applyBuy(fill(Side.BUY, quantity = 1_000L))

        assertEquals(1_000L, ledger.totalQty("000001.SZ"))
        assertEquals(0L, ledger.availableQty("000001.SZ"))
        assertEquals(1_000L, ledger.lockedTodayQty("000001.SZ"))

        ledger.settleDay(T0.plus(DatePeriod(days = 1)))

        assertEquals(1_000L, ledger.availableQty("000001.SZ"))
        assertEquals(0L, ledger.lockedTodayQty("000001.SZ"))
    }

    @Test fun `已有 Lot 时再次 applyBuy 抛错守护单 Lot 不变量`() {
        val ledger = PositionLedger()
        ledger.applyBuy(fill(Side.BUY, quantity = 1_000L))

        assertFailsWith<IllegalStateException> {
            ledger.applyBuy(fill(Side.BUY, quantity = 100L))
        }
    }

    @Test fun `applySell 必须全量清仓`() {
        val ledger = PositionLedger()
        ledger.applyBuy(fill(Side.BUY, quantity = 1_000L))

        assertFailsWith<IllegalStateException> {
            ledger.applySell(fill(Side.SELL, quantity = 500L))
        }

        ledger.applySell(fill(Side.SELL, quantity = 1_000L))
        assertEquals(0L, ledger.totalQty("000001.SZ"))
    }

    private fun fill(side: Side, quantity: Long): Fill = Fill(
        orderId = "order",
        tradeDate = T0,
        tsCode = "000001.SZ",
        side = side,
        quantity = quantity,
        price = 10.0,
        grossAmount = Money.ofTrade(10.0, quantity),
        commission = Money.ZERO,
        transferFee = Money.ZERO,
        stampDuty = Money.ZERO,
    )
}

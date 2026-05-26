package org.shiroumi.backtest.ledger

import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.output.CashFlowTag
import org.shiroumi.backtest.testing.T0
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountLedgerTest {

    @Test fun `apply BUY 写入持仓并扣减成交额和费用`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        ledger.apply(fill(side = Side.BUY, gross = 1_000.0, commission = 5.0, transfer = 0.01))

        assertEquals(Money.ofYuan(8_994.99), ledger.cash)
        assertEquals(100L, ledger.totalQty("000001.SZ"))
        assertEquals(3, ledger.cashFlowSnapshot().size)
        assertEquals(CashFlowTag.BUY, ledger.cashFlowSnapshot().first().tag)
    }

    @Test fun `apply SELL 全清持仓并增加扣费后现金`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        ledger.apply(fill(side = Side.BUY, gross = 1_000.0, commission = 5.0, transfer = 0.01))

        ledger.apply(fill(side = Side.SELL, gross = 1_100.0, commission = 5.0, transfer = 0.011, stamp = 0.55))

        assertEquals(0L, ledger.totalQty("000001.SZ"))
        assertEquals(Money.ofYuan(10_089.429), ledger.cash)
    }

    @Test fun `equity 等于现金加 RAW 收盘持仓市值`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        ledger.apply(fill(side = Side.BUY, gross = 1_000.0, commission = 5.0, transfer = 0.01))

        assertEquals(Money.ofYuan(9_994.99), ledger.equity(mapOf("000001.SZ" to 10.0)))
    }

    private fun fill(
        side: Side,
        gross: Double,
        commission: Double,
        transfer: Double,
        stamp: Double = 0.0,
    ): Fill = Fill(
        orderId = "order",
        tradeDate = T0,
        tsCode = "000001.SZ",
        side = side,
        quantity = 100L,
        price = gross / 100.0,
        grossAmount = Money.ofYuan(gross),
        commission = Money.ofYuan(commission),
        transferFee = Money.ofYuan(transfer),
        stampDuty = Money.ofYuan(stamp),
    )
}

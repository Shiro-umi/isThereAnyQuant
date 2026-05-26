package org.shiroumi.backtest.ledger

import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSettlerTest {

    @Test fun `preOpen 释放昨日买入 Lot 且 postClose 生成权益点和持仓快照`() {
        val ledger = AccountLedger(Money.ofYuan(10_000))
        ledger.apply(
            Fill(
                orderId = "buy",
                tradeDate = T0,
                tsCode = "000001.SZ",
                side = Side.BUY,
                quantity = 100L,
                price = 10.0,
                grossAmount = Money.ofYuan(1_000),
                commission = Money.ZERO,
                transferFee = Money.ZERO,
                stampDuty = Money.ZERO,
            ),
        )
        val settler = SessionSettler(ledger)

        settler.preOpen(T1)
        val settlement = settler.postClose(T1, mapOf("000001.SZ" to 11.0))

        assertEquals(100L, ledger.availableQty("000001.SZ"))
        assertEquals(Money.ofYuan(10_100), settlement.equityPoint.equity)
        assertEquals(1, settler.positionSnapshotHistory().size)
        assertEquals(1, settler.equityCurveSnapshot().size)
    }

    @Test fun `postClose 触发 CorporateActionApplier 入口`() {
        var called = false
        val ledger = AccountLedger(Money.ofYuan(10_000))
        val settler = SessionSettler(
            ledger = ledger,
            corporateActionApplier = CorporateActionApplier { date, account ->
                called = date == T1 && account === ledger
            },
        )

        settler.postClose(T1, emptyMap())

        assertEquals(true, called)
    }
}

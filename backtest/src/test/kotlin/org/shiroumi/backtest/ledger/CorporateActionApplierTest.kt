package org.shiroumi.backtest.ledger

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.output.CashFlowTag
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals

class CorporateActionApplierTest {

    @Test fun `现金分红增加现金且除权后权益曲线无跳变`() {
        val ts = "000001.SZ"
        val ledger = AccountLedger(Money.ofYuan(0))
        ledger.apply(fill(tsCode = ts, quantity = 100, price = 10.0))
        val settler = SessionSettler(
            ledger,
            InMemoryCorporateActionApplier(
                listOf(CorporateAction(exDate = T0, tsCode = ts, cashDividendPerShare = 1.0))
            ),
        )

        settler.postClose(T0, mapOf(ts to 10.0))
        val next = settler.postClose(T1, mapOf(ts to 9.0))

        assertEquals(Money.ofYuan(-5.001), settler.equityCurveSnapshot().first().equity)
        assertEquals(Money.ofYuan(-5.001), next.equityPoint.equity)
        assertEquals(CashFlowTag.DIVIDEND, ledger.cashFlowSnapshot().last().tag)
    }

    @Test fun `拆股调整持仓数量且权益不变`() {
        val ts = "000001.SZ"
        val ledger = AccountLedger(Money.ofYuan(0))
        ledger.apply(fill(tsCode = ts, quantity = 100, price = 10.0))
        val settler = SessionSettler(
            ledger,
            InMemoryCorporateActionApplier(
                listOf(CorporateAction(exDate = T0, tsCode = ts, shareMultiplier = 2.0))
            ),
        )

        settler.postClose(T0, mapOf(ts to 10.0))
        val next = settler.postClose(LocalDate(2024, 1, 4), mapOf(ts to 5.0))

        assertEquals(200L, ledger.totalQty(ts))
        assertEquals(Money.ofYuan(-5.001), next.equityPoint.equity)
    }

    private fun fill(tsCode: String, quantity: Long, price: Double): Fill {
        val gross = Money.ofTrade(price, quantity)
        return Fill(
            orderId = "corporate-action-test",
            tradeDate = T0,
            tsCode = tsCode,
            side = Side.BUY,
            quantity = quantity,
            price = price,
            grossAmount = gross,
            commission = Money.ofYuan(5),
            transferFee = Money.ofYuan(0.001),
            stampDuty = Money.ZERO,
        )
    }
}

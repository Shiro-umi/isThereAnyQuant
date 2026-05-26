package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.FakeLedger
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.defaultCosts
import org.shiroumi.backtest.testing.order
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CashAvailabilityRuleTest {
    private val rule = CashAvailabilityRule(defaultCosts)

    @Test fun `SELL 不走现金校验`() {
        val res = rule.apply(order(side = Side.SELL, quantity = 100L, limitPrice = 10.0), ctx())
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `BUY 现金充足通过`() {
        val res = rule.apply(
            order(side = Side.BUY, quantity = 10_000L, limitPrice = 10.0),
            ctx(ledger = FakeLedger(cash = Money.ofYuan(1_000_000))),
        )
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `BUY 现金不足兜底阻断`() {
        val res = rule.apply(
            order(side = Side.BUY, quantity = 10_000L, limitPrice = 10.0),
            ctx(ledger = FakeLedger(cash = Money.ofYuan(50_000))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.INSUFFICIENT_CASH, res.reason)
    }

    @Test fun `最低佣金 5 元被计入`() {
        // 成交额 100 × 1.00 = 100，佣金率 万 2.5 = 0.025 元，最低 5 元
        // 现金 105 元 + 1 分钱过户费 ≈ 不够，应被阻断
        val res = rule.apply(
            order(side = Side.BUY, quantity = 100L, limitPrice = 1.00),
            ctx(ledger = FakeLedger(cash = Money.ofYuan(104))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.INSUFFICIENT_CASH, res.reason)
    }
}

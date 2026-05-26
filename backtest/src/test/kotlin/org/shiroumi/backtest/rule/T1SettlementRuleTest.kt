package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.FakeLedger
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import org.shiroumi.backtest.testing.position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class T1SettlementRuleTest {
    private val rule = T1SettlementRule()

    @Test fun `BUY 不走该规则`() {
        val res = rule.apply(order(side = Side.BUY, quantity = 1_000L), ctx())
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `SELL 可卖数量充足放行`() {
        val ts = "000001.SZ"
        val ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, qty = 1_000L, settled = true)))
        val res = rule.apply(order(tsCode = ts, side = Side.SELL, quantity = 1_000L), ctx(ledger = ledger))
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `SELL 当日新买入未结算 → 阻断`() {
        val ts = "000001.SZ"
        val ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, qty = 1_000L, settled = false)))
        val res = rule.apply(order(tsCode = ts, side = Side.SELL, quantity = 1_000L), ctx(ledger = ledger))
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.INSUFFICIENT_QUANTITY_T1, res.reason)
    }

    @Test fun `SELL 无持仓 → 阻断`() {
        val res = rule.apply(order(tsCode = "000001.SZ", side = Side.SELL, quantity = 100L), ctx())
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.INSUFFICIENT_QUANTITY_T1, res.reason)
    }
}

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

class LotSizeRuleTest {
    private val rule = LotSizeRule()

    @Test fun `BUY 17_350 股向下取整到 17_300`() {
        val res = rule.apply(order(side = Side.BUY, quantity = 17_350L), ctx())
        assertTrue(res is RuleOutcome.Pass)
        assertEquals(17_300L, res.adjusted.quantity)
    }

    @Test fun `BUY 不足一手被阻断`() {
        val res = rule.apply(order(side = Side.BUY, quantity = 50L), ctx())
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.BELOW_LOT_SIZE, res.reason)
    }

    @Test fun `SELL 零股清仓放行`() {
        val ts = "000001.SZ"
        val ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, 80L)))
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, quantity = 80L),
            ctx(ledger = ledger),
        )
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `SELL 数量低于一手但与持仓不匹配 → 阻断`() {
        val ts = "000001.SZ"
        val ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, 200L)))
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, quantity = 50L),
            ctx(ledger = ledger),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.BELOW_LOT_SIZE, res.reason)
    }

    @Test fun `SELL 整手 + 零股的全量清仓放行（持仓 1080，订单 1080）`() {
        val ts = "000001.SZ"
        val ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, 1_080L)))
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, quantity = 1_080L),
            ctx(ledger = ledger),
        )
        assertTrue(res is RuleOutcome.Pass)
    }
}

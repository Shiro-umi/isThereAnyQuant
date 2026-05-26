package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TickSizeRuleTest {
    private val rule = TickSizeRule()

    @Test fun `BUY 价格向下取整到 0_01`() {
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, side = Side.BUY, hint = ExecutionHint.LIMIT, limitPrice = 11.234),
            ctx(),
        )
        assertTrue(res is RuleOutcome.Pass)
        assertEquals(11.23, res.adjusted.limitPrice)
    }

    @Test fun `SELL 价格向上取整到 0_01`() {
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, hint = ExecutionHint.LIMIT, limitPrice = 11.231),
            ctx(),
        )
        assertTrue(res is RuleOutcome.Pass)
        assertEquals(11.24, res.adjusted.limitPrice)
    }

    @Test fun `OPEN 提示从 bar open 推导价格`() {
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, hint = ExecutionHint.OPEN),
            ctx(quotes = mapOf(ts to candle(ts, open = 12.345))),
        )
        assertTrue(res is RuleOutcome.Pass)
        assertEquals(12.34, res.adjusted.limitPrice)
    }

    @Test fun `LIMIT 缺价时阻断`() {
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, hint = ExecutionHint.LIMIT, limitPrice = null),
            ctx(),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.SUSPENDED, res.reason)
    }
}

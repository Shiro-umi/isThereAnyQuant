package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import org.shiroumi.backtest.testing.position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidityRuleTest {

    @Test fun `未配置时直接通过`() {
        val rule = LiquidityRule(fractionLimit = null)
        val res = rule.apply(order(quantity = 10_000_000L), ctx())
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `委托等于 5 percent 上限放行`() {
        val rule = LiquidityRule(fractionLimit = 0.05)
        val ts = "000001.SZ"
        // 当日 1_000_000 股，5% = 50_000
        val res = rule.apply(
            order(tsCode = ts, quantity = 50_000L),
            ctx(quotes = mapOf(ts to candle(ts, volume = 1_000_000.0))),
        )
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `买单超出 5 percent 时截断到可成交整手数量`() {
        val rule = LiquidityRule(fractionLimit = 0.05)
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, quantity = 60_000L),
            ctx(quotes = mapOf(ts to candle(ts, volume = 1_000_000.0))),
        )

        assertTrue(res is RuleOutcome.Pass)
        assertEquals(50_000L, res.adjusted.quantity)
    }

    @Test fun `清仓卖单超出流动性上限时阻断以保持 1 买 1 卖全清语义`() {
        val rule = LiquidityRule(fractionLimit = 0.05)
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, quantity = 60_000L),
            ctx(
                quotes = mapOf(ts to candle(ts, volume = 1_000_000.0)),
                ledger = org.shiroumi.backtest.testing.FakeLedger(
                    initialPositions = mapOf(ts to position(ts, 60_000L, settled = true))
                ),
            ),
        )

        assertTrue(res is RuleOutcome.Block)
        assertEquals(BlockReason.LIQUIDITY_EXHAUSTED, res.reason)
    }

    @Test fun `当日成交量为 0 直接阻断`() {
        val rule = LiquidityRule(fractionLimit = 0.05)
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, quantity = 100L),
            ctx(quotes = mapOf(ts to candle(ts, volume = 0.0))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.LIQUIDITY_EXHAUSTED, res.reason)
    }
}

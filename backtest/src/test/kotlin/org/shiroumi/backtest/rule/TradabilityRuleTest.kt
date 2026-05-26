package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradabilityRuleTest {
    private val rule = TradabilityRule()

    @Test fun `通过：有行情、不停牌、不退市、不冻结`() {
        val ts = "000001.SZ"
        val res = rule.apply(order(tsCode = ts), ctx(quotes = mapOf(ts to candle(ts))))
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `阻断：停牌`() {
        val ts = "000001.SZ"
        val res = rule.apply(order(tsCode = ts), ctx(suspended = setOf(ts)))
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.SUSPENDED, res.reason)
    }

    @Test fun `阻断：退市优先于停牌`() {
        val ts = "000001.SZ"
        val res = rule.apply(order(tsCode = ts), ctx(delisted = setOf(ts), suspended = setOf(ts)))
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.DELISTED, res.reason)
    }

    @Test fun `阻断：IPO 冻结`() {
        val ts = "688001.SH"
        val res = rule.apply(order(tsCode = ts), ctx(ipoFrozen = setOf(ts), quotes = mapOf(ts to candle(ts))))
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.IPO_FROZEN, res.reason)
    }

    @Test fun `阻断：当日无行情视作停牌`() {
        val res = rule.apply(order(tsCode = "000001.SZ"), ctx())
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.SUSPENDED, res.reason)
    }
}

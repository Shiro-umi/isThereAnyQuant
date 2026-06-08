package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceLimitRuleTest {
    private val rule = PriceLimitRule()

    @Test fun `主板 BUY 命中涨停被阻断`() {
        val ts = "600000.SH" // 主板 ±10%
        val pre = 10.00
        val upper = 11.00 // 10.00 * 1.10 = 11.00
        val res = rule.apply(
            order(tsCode = ts, side = Side.BUY, limitPrice = upper),
            ctx(preClose = mapOf(ts to pre), quotes = mapOf(ts to candle(ts, open = upper))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.LIMIT_UP_BUY, res.reason)
    }

    @Test fun `主板 SELL 命中跌停被阻断`() {
        val ts = "600000.SH"
        val pre = 10.00
        val lower = 9.00
        val res = rule.apply(
            order(tsCode = ts, side = Side.SELL, limitPrice = lower),
            ctx(preClose = mapOf(ts to pre), quotes = mapOf(ts to candle(ts, open = lower))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.LIMIT_DOWN_SELL, res.reason)
    }

    @Test fun `创业板 ±20% BUY 在 19% 通过`() {
        val ts = "300750.SZ"
        val pre = 100.00
        val price = 119.00 // 涨停 120.00
        val res = rule.apply(
            order(tsCode = ts, side = Side.BUY, limitPrice = price),
            ctx(preClose = mapOf(ts to pre), quotes = mapOf(ts to candle(ts, open = price))),
        )
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `无 preClose 数据时放行`() {
        val ts = "000001.SZ"
        val res = rule.apply(
            order(tsCode = ts, side = Side.BUY, limitPrice = 50.0),
            ctx(quotes = mapOf(ts to candle(ts, open = 50.0))),
        )
        assertTrue(res is RuleOutcome.Pass)
    }

    @Test fun `市价单按 bar open 判断涨停`() {
        val ts = "600000.SH"
        val pre = 10.00
        val res = rule.apply(
            order(tsCode = ts, side = Side.BUY, limitPrice = null),
            ctx(preClose = mapOf(ts to pre), quotes = mapOf(ts to candle(ts, open = 11.00))),
        )
        assertTrue(res is RuleOutcome.Block); assertEquals(BlockReason.LIMIT_UP_BUY, res.reason)
    }

    @Test fun `当信号日已涨停且开启过滤时 BUY 被阻断`() {
        val ts = "600000.SH"
        val filterRule = PriceLimitRule(abandonIfSignalLimitUp = true)
        val res = filterRule.apply(
            order(tsCode = ts, side = Side.BUY),
            ctx(signalLimitUp = setOf(ts)),
        )
        assertTrue(res is RuleOutcome.Block)
        assertEquals(BlockReason.LIMIT_UP_BUY, res.reason)
    }

    @Test fun `当信号日已涨停但未开启过滤时 BUY 放行`() {
        val ts = "600000.SH"
        val noFilterRule = PriceLimitRule(abandonIfSignalLimitUp = false)
        val res = noFilterRule.apply(
            order(tsCode = ts, side = Side.BUY, limitPrice = 10.0),
            ctx(signalLimitUp = setOf(ts), preClose = mapOf(ts to 10.0), quotes = mapOf(ts to candle(ts, open = 10.0))),
        )
        assertTrue(res is RuleOutcome.Pass)
    }
}

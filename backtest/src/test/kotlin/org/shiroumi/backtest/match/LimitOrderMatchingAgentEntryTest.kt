package org.shiroumi.backtest.match

import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.validated
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent 买点价 → LIMIT 撮合触发单测（T+1 开盘按限价撮合口径）：
 *  1. 当日最低价触达买点价（bar.low <= entryPrice）→ 成交，且成交价 <= 买点价
 *  2. 当日最低价高于买点价（bar.low > entryPrice）→ 不成交（null），现金不变（无 Fill）
 *  3. 高开穿越：开盘高于买点价但盘中触达 → 成交价被限价封顶
 */
class LimitOrderMatchingAgentEntryTest {

    private val slippage = SlippageModel(SlippageConfig(basisPoints = 5))

    @Test
    fun `最低价触达买点价时成交且成交价不超过买点价`() {
        val entryPrice = 9.5
        val price = LimitOrderMatching.matchPrice(
            order = validated(side = Side.BUY, limitPrice = entryPrice, hint = ExecutionHint.LIMIT),
            // 开盘 9.6 高于买点价，但当日最低 9.3 <= 9.5 → 触达成交
            bar = candle(open = 9.6, high = 9.9, low = 9.3, close = 9.7),
            slippage = slippage,
        )

        assertTrue(price != null, "最低价触达买点价应成交")
        assertTrue(price!! <= entryPrice + 1e-9, "成交价不得突破买点价：$price <= $entryPrice")
    }

    @Test
    fun `最低价高于买点价时不成交 - 返回 null - 现金不变`() {
        val entryPrice = 9.5
        val price = LimitOrderMatching.matchPrice(
            order = validated(side = Side.BUY, limitPrice = entryPrice, hint = ExecutionHint.LIMIT),
            // 当日最低 9.6 > 9.5 → 全天未触达买点价
            bar = candle(open = 9.7, high = 10.1, low = 9.6, close = 9.9),
            slippage = slippage,
        )

        assertNull(price, "最低价未触达买点价不成交；无 Fill 即现金不变")
    }

    @Test
    fun `低开直接成交时成交价取开盘与买点价的较小值`() {
        val entryPrice = 9.5
        val price = LimitOrderMatching.matchPrice(
            order = validated(side = Side.BUY, limitPrice = entryPrice, hint = ExecutionHint.LIMIT),
            // 开盘 9.0 已低于买点价：参考价取 min(开盘, 限价)=9.0，叠滑点后再封顶买点价
            bar = candle(open = 9.0, high = 9.4, low = 8.8, close = 9.2),
            slippage = slippage,
        )

        assertTrue(price != null)
        assertTrue(price!! <= entryPrice + 1e-9, "成交价不得突破买点价")
        assertTrue(price <= 9.0 * (1.0 + 5 / 10_000.0) + 1e-9, "低开按开盘参考价叠滑点")
    }
}

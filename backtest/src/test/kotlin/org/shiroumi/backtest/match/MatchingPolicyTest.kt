package org.shiroumi.backtest.match

import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.validated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchingPolicyTest {

    @Test fun `OPEN 撮合按开盘价叠加买入滑点`() {
        val price = OpenPriceMatching.matchPrice(
            order = validated(side = Side.BUY, limitPrice = 10.0, hint = ExecutionHint.OPEN),
            bar = candle(open = 10.0),
            slippage = SlippageModel(SlippageConfig(basisPoints = 5)),
        )

        assertEquals(10.005, price, 0.000_001)
    }

    @Test fun `VWAP 撮合在 Candle 无 VWAP 字段时使用 OHLC 近似`() {
        val price = VwapMatching.matchPrice(
            order = validated(hint = ExecutionHint.VWAP),
            bar = candle(open = 10.0, high = 11.0, low = 9.0, close = 10.0),
            slippage = SlippageModel(SlippageConfig(basisPoints = 0)),
        )

        assertEquals(10.0, price, 0.000_001)
    }

    @Test fun `CLOSE 撮合按收盘价叠加卖出滑点`() {
        val price = ClosePriceMatching.matchPrice(
            order = validated(side = Side.SELL, limitPrice = 10.0, hint = ExecutionHint.CLOSE),
            bar = candle(close = 10.0),
            slippage = SlippageModel(SlippageConfig(basisPoints = 5)),
        )

        assertEquals(9.995, price, 0.000_001)
    }

    @Test fun `限价买单未触达最低价时不成交`() {
        val price = LimitOrderMatching.matchPrice(
            order = validated(side = Side.BUY, limitPrice = 10.0, hint = ExecutionHint.LIMIT),
            bar = candle(open = 10.5, high = 11.0, low = 10.2, close = 10.4),
            slippage = SlippageModel(SlippageConfig(basisPoints = 0)),
        )

        assertNull(price)
    }

    @Test fun `限价卖单触达后成交价不低于限价`() {
        val price = LimitOrderMatching.matchPrice(
            order = validated(side = Side.SELL, limitPrice = 10.5, hint = ExecutionHint.LIMIT),
            bar = candle(open = 10.0, high = 10.8, low = 9.9, close = 10.2),
            slippage = SlippageModel(SlippageConfig(basisPoints = 5)),
        )

        assertEquals(10.5, price!!, 0.000_001)
    }
}

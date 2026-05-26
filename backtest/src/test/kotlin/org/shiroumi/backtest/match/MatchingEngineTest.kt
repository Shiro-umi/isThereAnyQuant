package org.shiroumi.backtest.match

import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.validated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingEngineTest {

    @Test fun `撮合生成 Fill 并计算买入三费`() {
        val engine = MatchingEngine(
            policy = OpenPriceMatching,
            slippage = SlippageModel(SlippageConfig(basisPoints = 5)),
            costs = CostModel(),
        )

        val fills = engine.match(
            orders = listOf(validated(side = Side.BUY, quantity = 100L)),
            ctx = ctx(quotes = mapOf("000001.SZ" to candle(open = 10.0))),
        )

        val fill = fills.single()
        assertEquals(T1, fill.tradeDate)
        assertEquals(10.005, fill.price, 0.000_001)
        assertEquals(Money.ofYuan(1_000.5), fill.grossAmount)
        assertEquals(Money.ofYuan(5.0), fill.commission)
        assertEquals(Money.ofYuan(0.010005), fill.transferFee)
        assertEquals(Money.ZERO, fill.stampDuty)
    }

    @Test fun `卖出 Fill 计算单边印花税`() {
        val engine = MatchingEngine(
            policy = ClosePriceMatching,
            slippage = SlippageModel(SlippageConfig(basisPoints = 0)),
            costs = CostModel(),
        )

        val fill = engine.match(
            orders = listOf(validated(side = Side.SELL, quantity = 100L)),
            ctx = ctx(quotes = mapOf("000001.SZ" to candle(close = 10.0))),
        ).single()

        assertEquals(Money.ofYuan(1_000.0), fill.grossAmount)
        assertEquals(Money.ofYuan(0.5), fill.stampDuty)
    }

    @Test fun `撮合条件未触发时不生成 Fill 并记录 Unfilled`() {
        val engine = MatchingEngine(
            policy = LimitOrderMatching,
            slippage = SlippageModel(SlippageConfig(basisPoints = 0)),
            costs = CostModel(),
        )

        val fills = engine.match(
            orders = listOf(validated(side = Side.BUY, limitPrice = 10.0)),
            ctx = ctx(quotes = mapOf("000001.SZ" to candle(open = 10.5, high = 11.0, low = 10.2, close = 10.4))),
        )

        assertTrue(fills.isEmpty())
        assertEquals("000001.SZ", engine.unfilledSnapshot().single().tsCode)
    }
}

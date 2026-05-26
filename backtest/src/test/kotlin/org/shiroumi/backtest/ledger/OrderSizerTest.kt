package org.shiroumi.backtest.ledger

import org.shiroumi.backtest.domain.AuditReason
import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.FakeLedger
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderSizerTest {
    private val sizer = OrderSizer()

    @Test fun `空仓且目标权重大于 0 生成买单并按 100 股取整`() {
        val result = sizer.size(
            listOf(target(mapOf("000001.SZ" to 0.2))),
            FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx(quotes = mapOf("000001.SZ" to candle("000001.SZ", open = 11.5))),
        )

        assertEquals(1, result.draftOrders.size)
        assertEquals(Side.BUY, result.draftOrders.single().side)
        assertEquals(17_300L, result.draftOrders.single().quantity)
    }

    @Test fun `持仓期间目标权重变化被吸收为 HOLD`() {
        val ts = "000001.SZ"
        val result = sizer.size(
            listOf(target(mapOf(ts to 0.3))),
            FakeLedger(initialPositions = mapOf(ts to position(ts, 10_000L))),
            ctx(quotes = mapOf(ts to candle(ts, open = 10.0))),
        )

        assertTrue(result.draftOrders.isEmpty())
        assertTrue(result.audits.isEmpty())
    }

    @Test fun `目标组合剔除已有持仓生成全量卖单`() {
        val ts = "000001.SZ"
        val result = sizer.size(
            listOf(target(emptyMap())),
            FakeLedger(initialPositions = mapOf(ts to position(ts, 10_080L))),
            ctx(quotes = mapOf(ts to candle(ts, open = 10.0))),
        )

        assertEquals(Side.SELL, result.draftOrders.single().side)
        assertEquals(10_080L, result.draftOrders.single().quantity)
    }

    @Test fun `显式 BUY 已持仓被 ALREADY_HOLDING 阻断`() {
        val ts = "000001.SZ"
        val result = sizer.size(
            listOf(intent(ts, Side.BUY, 0.2)),
            FakeLedger(initialPositions = mapOf(ts to position(ts, 100L))),
            ctx(quotes = mapOf(ts to candle(ts))),
        )

        assertEquals(BlockReason.ALREADY_HOLDING, result.blockedOrders.single().reason)
        assertTrue(result.audits.isEmpty())
    }

    @Test fun `同日同标的 BUY 与 SELL 被 SAME_DAY_REVERSE 阻断`() {
        val ts = "000001.SZ"
        val result = sizer.size(
            listOf(target(mapOf(ts to 0.2)), intent(ts, Side.SELL, null)),
            FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx(quotes = mapOf(ts to candle(ts))),
        )

        assertTrue(result.draftOrders.isEmpty())
        assertEquals(BlockReason.SAME_DAY_REVERSE, result.blockedOrders.single().reason)
    }

    @Test fun `现金不足时买单按比例缩放并写 CASH_SCALED audit`() {
        val result = sizer.size(
            listOf(target(mapOf("000001.SZ" to 0.8, "600000.SH" to 0.8))),
            FakeLedger(cash = Money.ofYuan(10_000)),
            ctx(
                quotes = mapOf(
                    "000001.SZ" to candle("000001.SZ", open = 10.0),
                    "600000.SH" to candle("600000.SH", open = 10.0),
                ),
            ),
        )

        assertTrue(result.draftOrders.all { it.side == Side.BUY && it.quantity % 100L == 0L })
        assertTrue(result.draftOrders.sumOf { it.quantity } < 1_600L)
        assertEquals(AuditReason.CASH_SCALED, result.audits.first().auditReason)
        assertTrue(result.audits.first().scaleRatio != null)
    }

    private fun target(weights: Map<String, Double>) = StrategyDecision.TargetPortfolioDecision(
        effectiveDate = T1,
        reason = "target",
        targetWeights = weights,
        sentimentExposure = weights.values.sum(),
    )

    private fun intent(tsCode: String, side: Side, weight: Double?) = StrategyDecision.TradeIntentDecision(
        effectiveDate = T1,
        reason = "intent",
        tsCode = tsCode,
        side = side,
        weight = weight,
        hint = ExecutionHint.OPEN,
    )
}

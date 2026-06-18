package org.shiroumi.backtest.ledger

import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.FakeLedger
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OrderSizer agent 买点价（LIMIT）现金口径单测：
 *  - LIMIT hint 用 agent limitPrice 折股与定限价，不用开盘价
 *  - 两个边界：开盘 > 限价、开盘 < 限价；折股与限价都以 agent 价为准
 *  - requiredCash 用 agent 价保守估现金，缩放后不超支，股数 lot 对齐
 *  - OPEN hint 仍用开盘价，忽略 limitPrice
 */
class OrderSizerLimitPriceTest {
    private val sizer = OrderSizer()

    @Test
    fun `LIMIT hint 用 limitPrice 折股而非开盘价 - 开盘高于限价边界`() {
        // 开盘 11.5 > 限价 9.0：折股必须用 9.0（55500 股），而非用 11.5（43400 股）
        val result = sizer.size(
            listOf(intent("000001.SZ", Side.BUY, weight = 0.5, hint = ExecutionHint.LIMIT, limitPrice = 9.0)),
            FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx(quotes = mapOf("000001.SZ" to candle("000001.SZ", open = 11.5))),
        )

        assertEquals(1, result.draftOrders.size)
        val order = result.draftOrders.single()
        assertEquals(ExecutionHint.LIMIT, order.hint)
        assertEquals(9.0, order.limitPrice, "限价 = agent 买点价")
        assertEquals(55_500L, order.quantity, "1M * 0.5 / 9 = 55555.5 → lot 对齐 55500")
    }

    @Test
    fun `LIMIT hint 用 limitPrice 折股而非开盘价 - 开盘低于限价边界`() {
        // 开盘 7.0 < 限价 8.0：折股仍用 8.0（保守估现金，62500 股），而非用 7.0（71400 股）
        val result = sizer.size(
            listOf(intent("000001.SZ", Side.BUY, weight = 0.5, hint = ExecutionHint.LIMIT, limitPrice = 8.0)),
            FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx(quotes = mapOf("000001.SZ" to candle("000001.SZ", open = 7.0))),
        )

        assertEquals(1, result.draftOrders.size)
        val order = result.draftOrders.single()
        assertEquals(ExecutionHint.LIMIT, order.hint)
        assertEquals(8.0, order.limitPrice, "限价 = agent 买点价")
        assertEquals(62_500L, order.quantity, "1M * 0.5 / 8 = 62500 → lot 对齐 62500")
    }

    @Test
    fun `requiredCash 用 agent 价保守估现金 - 缩放后不超支且 lot 对齐`() {
        // 满仓 weight=1.0，agent 价 9.0，初始现金正好 1M：含佣金+过户费后毛额超现金 → 触发缩放。
        val costs = CostModelConfig()
        val sizerWithCosts = OrderSizer(costs = costs)
        val cash = Money.ofYuan(1_000_000)
        val result = sizerWithCosts.size(
            listOf(intent("000001.SZ", Side.BUY, weight = 1.0, hint = ExecutionHint.LIMIT, limitPrice = 9.0)),
            FakeLedger(cash = cash),
            ctx(quotes = mapOf("000001.SZ" to candle("000001.SZ", open = 11.5))),
        )

        assertEquals(1, result.draftOrders.size)
        val order = result.draftOrders.single()
        assertEquals(9.0, order.limitPrice, "缩放仍以 agent 价为限价")
        assertEquals(0L, order.quantity % 100L, "缩放后仍 lot 对齐")

        // 用 agent 价口径核算所需现金：gross = price*qty，佣金=max(gross*rate, minCommission)，过户费=gross*transferRate
        val gross = order.limitPrice!! * order.quantity
        val commission = maxOf(gross * costs.commissionRate, costs.minCommission.toDouble())
        val transferFee = gross * costs.transferFeeRate
        val requiredYuan = gross + commission + transferFee
        assertTrue(requiredYuan <= cash.toDouble() + 1e-6, "按 agent 价保守估现金永不超支：required=$requiredYuan cash=${cash.toDouble()}")
    }

    @Test
    fun `OPEN hint 仍用开盘价 - 忽略 limitPrice 字段`() {
        val result = sizer.size(
            listOf(intent("000001.SZ", Side.BUY, weight = 0.5, hint = ExecutionHint.OPEN, limitPrice = 9.0)),
            FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx(quotes = mapOf("000001.SZ" to candle("000001.SZ", open = 11.5))),
        )

        assertEquals(1, result.draftOrders.size)
        val order = result.draftOrders.single()
        assertEquals(43_400L, order.quantity, "OPEN：1M * 0.5 / 11.5 = 43478 → lot 对齐 43400")
        assertEquals(11.5, order.limitPrice, "OPEN：限价占位为开盘价")
    }

    private fun intent(
        tsCode: String,
        side: Side,
        weight: Double,
        hint: ExecutionHint = ExecutionHint.OPEN,
        limitPrice: Double? = null,
    ) = StrategyDecision.TradeIntentDecision(
        effectiveDate = T1,
        reason = "test",
        tsCode = tsCode,
        side = side,
        weight = weight,
        hint = hint,
        limitPrice = limitPrice,
    )
}

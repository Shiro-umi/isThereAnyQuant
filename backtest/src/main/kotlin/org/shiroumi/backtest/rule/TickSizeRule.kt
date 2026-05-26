package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 价格档对齐规则。
 *
 * 职责：
 *  1. 把订单的参考价补全为 [DraftOrder.limitPrice]——根据 [ExecutionHint] 从 [MatchingContext.bar] 推导
 *  2. 把参考价对齐到 0.01 元：买向下取整、卖向上取整（让模拟比实务略保守）
 *
 * 边界：
 *  - LIMIT 单要求订单本身带 limitPrice，否则视作无法定价 → [BlockReason.SUSPENDED]（无价不能撮合）
 *  - 市价类（OPEN/VWAP/CLOSE）若 ctx 无 bar 行情，前序 [TradabilityRule] 已经阻断，
 *    这里仍做兜底：取不到价格则 SUSPENDED
 */
class TickSizeRule : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        val refPrice = referencePrice(order, ctx)
            ?: return RuleOutcome.Block(BlockReason.SUSPENDED, "${order.tsCode} 当日无法定价")

        val aligned = align(refPrice, order.side)
        if (aligned <= 0.0) {
            return RuleOutcome.Block(BlockReason.SUSPENDED, "${order.tsCode} 价格档对齐后为非正")
        }
        return RuleOutcome.Pass(order.copy(limitPrice = aligned))
    }

    private fun referencePrice(order: DraftOrder, ctx: MatchingContext): Double? {
        if (order.hint == ExecutionHint.LIMIT) {
            return order.limitPrice
        }
        val bar = ctx.bar(order.tsCode) ?: return null
        return when (order.hint) {
            ExecutionHint.OPEN -> bar.open.toDouble()
            ExecutionHint.CLOSE -> bar.close.toDouble()
            ExecutionHint.VWAP -> (bar.open + bar.high + bar.low + bar.close) / 4.0
            ExecutionHint.LIMIT -> order.limitPrice
        }
    }

    private fun align(price: Double, side: Side): Double {
        val mode = if (side == Side.BUY) RoundingMode.FLOOR else RoundingMode.CEILING
        return BigDecimal.valueOf(price).setScale(2, mode).toDouble()
    }
}

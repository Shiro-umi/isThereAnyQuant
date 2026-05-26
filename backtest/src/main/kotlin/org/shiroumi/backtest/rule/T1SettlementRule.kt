package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side

/**
 * T+1 锁仓规则（对齐 docs/architecture/backtest-engine-design.md §2.5）。
 *
 * 卖出时，可卖数量必须 ≥ 委托数量：
 *  - `availableQty` 只统计 `settled=true` 的 Lot；
 *  - 当日新买入的 Lot `settled=false`，在收盘结算前不计入可卖。
 *
 * 买单不在此规则范围内。
 */
class T1SettlementRule : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        if (order.side != Side.SELL) return RuleOutcome.Pass(order)
        val available = ctx.ledger.availableQty(order.tsCode)
        if (available < order.quantity) {
            return RuleOutcome.Block(
                BlockReason.INSUFFICIENT_QUANTITY_T1,
                "${order.tsCode} 可卖数量 $available 少于委托 ${order.quantity}（T+1 锁仓）",
            )
        }
        return RuleOutcome.Pass(order)
    }
}

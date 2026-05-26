package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side

/**
 * 一手 100 股的整手规则。
 *
 * 实务约束（对齐 docs/architecture/backtest-engine-design.md §2.2）：
 *  - 买入：必须为 100 股整数倍，未对齐时向下取整。取整后若不足 100 → [BlockReason.BELOW_LOT_SIZE]
 *  - 卖出：允许一次性卖出"零股余额"（持仓 < 100 时整笔卖出），其它情况要求 100 股整数倍
 *
 * 与 1 买 1 卖语义协同：卖出数量校验放在更上游的 OrderSizer 中（必须等于 totalQty），
 * 这里仅做"整手 / 零股清仓"的形式校验。
 */
class LotSizeRule : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        if (order.quantity <= 0L) {
            return RuleOutcome.Block(BlockReason.BELOW_LOT_SIZE, "数量必须为正：${order.quantity}")
        }
        return when (order.side) {
            Side.BUY -> validateBuy(order)
            Side.SELL -> validateSell(order, ctx)
        }
    }

    private fun validateBuy(order: DraftOrder): RuleOutcome {
        val aligned = (order.quantity / LOT) * LOT
        if (aligned < LOT) {
            return RuleOutcome.Block(
                BlockReason.BELOW_LOT_SIZE,
                "买入数量 ${order.quantity} 不足一手 $LOT 股",
            )
        }
        return if (aligned == order.quantity) RuleOutcome.Pass(order)
        else RuleOutcome.Pass(order.copy(quantity = aligned))
    }

    private fun validateSell(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        val totalQty = ctx.ledger.totalQty(order.tsCode)
        // 零股清仓豁免：当持仓本身就是零股 (< 100) 且订单数量等于持仓数量，允许通过
        if (totalQty in 1L until LOT && order.quantity == totalQty) {
            return RuleOutcome.Pass(order)
        }
        val aligned = (order.quantity / LOT) * LOT
        if (aligned < LOT) {
            return RuleOutcome.Block(
                BlockReason.BELOW_LOT_SIZE,
                "卖出数量 ${order.quantity} 不足一手 $LOT 股 且不构成零股清仓（持仓 $totalQty）",
            )
        }
        // 持仓中既有整手又含零股的清仓场景（例如配股拆股导致），允许一次性卖出
        if (order.quantity == totalQty) {
            return RuleOutcome.Pass(order)
        }
        return if (aligned == order.quantity) RuleOutcome.Pass(order)
        else RuleOutcome.Pass(order.copy(quantity = aligned))
    }

    private companion object {
        const val LOT = 100L
    }
}

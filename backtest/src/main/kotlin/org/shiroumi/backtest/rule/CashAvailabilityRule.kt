package org.shiroumi.backtest.rule

import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side

/**
 * 现金校验规则（兜底）。
 *
 * 顺位：
 *  1. [org.shiroumi.backtest.ledger.OrderSizer] 已经对买入合计做过现金校验与按比例缩放
 *     （audit 写入 CASH_SCALED）；
 *  2. 经过校验仍然超出可用现金（多组 BUY 之间的舍入误差、或者上游绕过 OrderSizer 直接送的订单）→
 *     本规则兜底阻断为 [BlockReason.INSUFFICIENT_CASH]。
 *
 * 仅 [Side.BUY] 走该规则；SELL 不冻结现金。
 *
 * 注意：此处的"预估费用"按 [CostModelConfig.commissionRate] 与 [CostModelConfig.transferFeeRate] 估算，
 * 不包含 stamp duty（买入无印花税）。最小佣金按 [CostModelConfig.minCommission] 兜底。
 */
class CashAvailabilityRule(private val costs: CostModelConfig) : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        if (order.side != Side.BUY) return RuleOutcome.Pass(order)
        val price = order.limitPrice
            ?: error("CashAvailabilityRule 之前必须有 TickSizeRule 完成 limitPrice 补全")
        val gross = Money.ofTrade(price, order.quantity)
        val commission = maxOf(gross * costs.commissionRate, costs.minCommission)
        val transferFee = gross * costs.transferFeeRate
        val required = gross + commission + transferFee
        val available = ctx.ledger.cash
        if (required > available) {
            return RuleOutcome.Block(
                BlockReason.INSUFFICIENT_CASH,
                "${order.tsCode} 需要 $required（含费 ${commission + transferFee}），可用现金仅 $available",
            )
        }
        return RuleOutcome.Pass(order)
    }
}

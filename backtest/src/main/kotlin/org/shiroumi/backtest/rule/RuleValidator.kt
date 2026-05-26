package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockedOrder
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.ValidatedOrder

/**
 * 规则校验链：把一个 [DraftOrder] 顺次喂给若干条 [MarketRule]。
 *
 *  - 任一规则返回 [RuleOutcome.Block] → 短路返回 [ValidationResult.Blocked]
 *  - 所有规则返回 [RuleOutcome.Pass] → 输出 [ValidationResult.Validated]
 *
 * 调整语义：[RuleOutcome.Pass.adjusted] 是**累计应用**的——
 * 第 N 条规则看到的是前 N-1 条规则修订后的订单。
 * 这让"价格档对齐"、"100 股取整"等修订能在校验链内部完成，
 * 而 MatchingEngine 拿到的总是已经合规的订单。
 *
 * 经过校验后的最终订单需要一个非空的 limitPrice（撮合时的参考价）；
 * 若校验通过但 limitPrice 仍为空，视为规则链未完成必要的价格补全，抛 IllegalStateException。
 */
class RuleValidator(private val rules: List<MarketRule>) {

    fun validate(order: DraftOrder, ctx: MatchingContext): ValidationResult {
        var current = order
        for (rule in rules) {
            when (val outcome = rule.apply(current, ctx)) {
                is RuleOutcome.Pass -> current = outcome.adjusted
                is RuleOutcome.Block -> return ValidationResult.Blocked(
                    BlockedOrder(source = order, reason = outcome.reason, detail = outcome.detail)
                )
            }
        }
        val limit = current.limitPrice
            ?: error("规则链未完成价格补全（订单 ${current.orderId}），limitPrice 不能为空")
        return ValidationResult.Validated(
            ValidatedOrder(
                orderId = current.orderId,
                effectiveDate = current.effectiveDate,
                tsCode = current.tsCode,
                side = current.side,
                quantity = current.quantity,
                limitPrice = limit,
                hint = current.hint,
            )
        )
    }
}

sealed interface ValidationResult {
    data class Validated(val order: ValidatedOrder) : ValidationResult
    data class Blocked(val blocked: BlockedOrder) : ValidationResult
}

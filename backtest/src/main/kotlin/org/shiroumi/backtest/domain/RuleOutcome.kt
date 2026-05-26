package org.shiroumi.backtest.domain

import kotlinx.serialization.Serializable

/**
 * 单条 [org.shiroumi.backtest.rule.MarketRule] 应用后的结果。
 *
 *  - [Pass] —— 规则通过；可能伴随订单微调（如价格按 0.01 取整、数量按 100 股取整）
 *  - [Block] —— 规则拒绝；订单不进入撮合，需要记入审计
 *
 * RuleValidator 串联多条规则时，遇到 [Block] 即短路返回。
 */
@Serializable
sealed interface RuleOutcome {
    @Serializable
    data class Pass(val adjusted: DraftOrder) : RuleOutcome

    @Serializable
    data class Block(val reason: BlockReason, val detail: String) : RuleOutcome
}

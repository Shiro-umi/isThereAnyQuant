package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome

/**
 * 可交易性规则。任意一项命中即阻断：
 *
 *  - 停牌：当日不可交易 → [BlockReason.SUSPENDED]
 *  - 退市：标的已下市 → [BlockReason.DELISTED]
 *  - IPO 冻结：新股前 N 个交易日 → [BlockReason.IPO_FROZEN]
 *  - 无行情：当日没有 Candle（既不是停牌也不是退市，可能数据缺失）→ 视作 SUSPENDED
 *
 * 顺序：先判退市（终态）→ 再判停牌 → 最后判 IPO 冻结。
 */
class TradabilityRule : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        val tsCode = order.tsCode
        if (ctx.isDelisted(tsCode)) {
            return RuleOutcome.Block(BlockReason.DELISTED, "$tsCode 已退市")
        }
        if (ctx.isSuspended(tsCode)) {
            return RuleOutcome.Block(BlockReason.SUSPENDED, "$tsCode 当日停牌")
        }
        if (ctx.isIpoFrozen(tsCode)) {
            return RuleOutcome.Block(BlockReason.IPO_FROZEN, "$tsCode 处于新股冻结期")
        }
        if (ctx.bar(tsCode) == null) {
            return RuleOutcome.Block(BlockReason.SUSPENDED, "$tsCode 当日无行情数据")
        }
        return RuleOutcome.Pass(order)
    }
}

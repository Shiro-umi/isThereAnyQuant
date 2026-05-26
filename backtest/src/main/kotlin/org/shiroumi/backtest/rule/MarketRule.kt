package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome

/**
 * A 股交易规则的纯函数接口。
 *
 * 给定订单 + 撮合上下文，输出 [RuleOutcome.Pass]（可能携带订单微调）或 [RuleOutcome.Block]。
 *
 * 规则实现必须满足：
 *  - **无副作用**：禁止修改 [MatchingContext.ledger]，禁止持有外部可变状态；
 *  - **可单测**：单条规则可在内存中构造 ctx 并独立验证；
 *  - **幂等**：同样输入多次应用结果一致。
 *
 * 详见 docs/architecture/backtest-engine-design.md §2 与 §4.2 校验链时序图。
 */
fun interface MarketRule {
    fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome
}

package org.shiroumi.backtest.domain

/**
 * 账户私有域对外暴露的**只读**视图。
 *
 * 用于 [org.shiroumi.backtest.ledger.OrderSizer] / RuleValidator 在不能修改账户的前提下读取
 * 当前权益、可用现金与持仓数量。任何修改账户的入口都被收敛到
 * [org.shiroumi.backtest.ledger.AccountLedger.apply]，确保撮合是唯一写入者。
 *
 * 该接口**不允许**出现在 [StrategyDecision] 子类型或 StrategyDecisionFeed 接口签名中——
 * 一旦泄漏，意味着策略层能感知账户状态，违反设计文档 §1.5 的硬约束。
 */
interface LedgerView {
    /** 当前可用现金（不含未成交订单的冻结）。 */
    val cash: Money

    /** 当前所有持仓的只读快照（按标的代码索引）。 */
    val positions: Map<String, StockPosition>

    /** 当前 mark-to-market 权益。需要传入"标的→当日参考价"映射。 */
    fun equity(quote: Map<String, Double>): Money

    /** 查询某标的当前可卖数量（settled 的 Lot 数量）。 */
    fun availableQty(tsCode: String): Long = positions[tsCode]?.availableQty ?: 0L

    /** 查询某标的当前总持仓数量。 */
    fun totalQty(tsCode: String): Long = positions[tsCode]?.totalQty ?: 0L
}

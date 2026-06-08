package org.shiroumi.backtest.rule

import org.shiroumi.backtest.config.BacktestConfig

/**
 * 默认规则链装配（对齐 docs/architecture/backtest-engine-design.md §4.2 时序图）：
 *
 *   TradabilityRule
 *     → PriceLimitRule
 *     → TickSizeRule         （补齐 limitPrice + 按 0.01 取整）
 *     → LotSizeRule          （100 股取整 / 零股清仓）
 *     → T1SettlementRule     （SELL：可卖数量校验）
 *     → CashAvailabilityRule （BUY：现金校验兜底）
 *     → LiquidityRule        （可选）
 *
 * 注意：T1 / Cash 两条规则各自只对一侧生效（SELL / BUY），无需在链中再做分流。
 */
object DefaultRuleChain {
    fun build(config: BacktestConfig): RuleValidator = RuleValidator(
        listOf(
            TradabilityRule(),
            PriceLimitRule(
                mainBoardLimit = config.rules.priceLimitMainBoard,
                growthBoardLimit = config.rules.priceLimitGrowthBoard,
                abandonIfSignalLimitUp = config.rules.abandonIfSignalLimitUp,
            ),
            TickSizeRule(),
            LotSizeRule(),
            T1SettlementRule(),
            CashAvailabilityRule(config.costs),
            LiquidityRule(config.rules.liquidityFractionLimit),
        )
    )
}

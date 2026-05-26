package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 回测决策输入源。
 *
 * 策略侧只输出目标组合或显式交易意图；账户现金、股数和持仓状态不允许进入本接口。
 */
fun interface StrategyDecisionFeed {
    fun decisionsFor(date: LocalDate): List<StrategyDecision>
}

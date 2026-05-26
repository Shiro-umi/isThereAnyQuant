package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 单测和离线回放用内存决策源。
 */
class InMemoryDecisionFeed(decisions: List<StrategyDecision>) : StrategyDecisionFeed {
    private val byDate: Map<LocalDate, List<StrategyDecision>> = decisions.groupBy { it.effectiveDate }

    override fun decisionsFor(date: LocalDate): List<StrategyDecision> = byDate[date].orEmpty()
}

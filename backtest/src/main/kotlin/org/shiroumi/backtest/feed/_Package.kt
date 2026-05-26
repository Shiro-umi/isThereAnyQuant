/**
 * 策略决策适配层：StrategyDecisionFeed 及其各实现。
 *
 * **强制约束**：适配器在转换过程中必须主动剥离任何账户字段
 * （quantity / cashAmount / currentPosition / availableCash 等），
 * 确保进入回测引擎的 [StrategyDecision] 不携带账户状态。
 *
 * 详见 docs/architecture/backtest-engine-design.md §1.2、§5.2、§8。
 */
package org.shiroumi.backtest.feed

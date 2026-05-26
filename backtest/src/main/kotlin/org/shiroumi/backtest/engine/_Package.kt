/**
 * 回测引擎顶层：组装 [BacktestScheduler] 等核心组件并驱动主循环。
 *
 * 入口类：BacktestEngine（待实现，M6 阶段落地）。
 *
 * 边界：本包**不包含**任何交易决策、规则细节或撮合算法；仅装配 + 串流。
 * 详见 docs/architecture/backtest-engine-design.md §3、§5.2。
 */
package org.shiroumi.backtest.engine

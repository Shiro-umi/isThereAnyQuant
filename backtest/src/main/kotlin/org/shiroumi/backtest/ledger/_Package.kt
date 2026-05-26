/**
 * 账户私有域：AccountLedger / PositionLedger / OrderSizer / SessionSettler。
 *
 * 这是设计文档的**核心边界**——账户状态对策略层完全不可见：
 *  - 策略只产出权重 / 方向；
 *  - 权重 → 金额 → 股数、现金缩放、T+1 锁仓、1 买 1 卖语义全部发生在此包内。
 *
 * 详见 docs/architecture/backtest-engine-design.md §1.2、§1.5、§2.5、§2.6、§3.3。
 */
package org.shiroumi.backtest.ledger

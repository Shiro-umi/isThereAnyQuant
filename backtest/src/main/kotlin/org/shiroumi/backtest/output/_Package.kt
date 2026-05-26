/**
 * 输出层：SimulationResult 聚合、TradeAuditWriter、PerformanceReporter。
 *
 * 输出只回流给调用方（CLI / 报表服务），**不回流给策略**。
 * 详见 docs/architecture/backtest-engine-design.md §1.3、§3.3。
 */
package org.shiroumi.backtest.output

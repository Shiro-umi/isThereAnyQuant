/**
 * A 股交易规则引擎：所有 [MarketRule] 实现 + [RuleValidator] 校验链。
 *
 * 每条规则均为纯函数（输入订单 + 上下文，输出 Pass/Block），可独立单测、可替换。
 * 详见 docs/architecture/backtest-engine-design.md §2、§4.2。
 */
package org.shiroumi.backtest.rule

/**
 * 本地文件模式工作区目录管理。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.2 / §11.4.1：
 * `.backtest/{runId}/` 下挂 `decisions/` 与 `output/` 两个子目录，
 * 由 [org.shiroumi.backtest.workspace.BacktestWorkspace] 统一持有。
 */
package org.shiroumi.backtest.workspace

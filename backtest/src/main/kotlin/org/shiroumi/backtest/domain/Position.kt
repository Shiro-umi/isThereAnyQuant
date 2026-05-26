package org.shiroumi.backtest.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 一笔买入形成的持仓批次。
 *
 * 在本回测系统中，**同一标的同时最多只能存在一个 Lot**（1 买 1 卖语义，详见
 * docs/architecture/backtest-engine-design.md §2.6）。这意味着 Lot 的 `quantity`
 * 就是该标的当前的全部持仓——清仓必须把这个 Lot 整笔卖光，下一次买入会创建新的 Lot。
 *
 * @param buyDate 买入日期（成交日，不是委托日）
 * @param quantity 持仓股数
 * @param cost 买入成本价（RAW 原始价，含成本但不含费用）
 * @param settled 是否已度过 T+1：true=可卖，false=锁仓
 */
@Serializable
data class Lot(
    val buyDate: LocalDate,
    val quantity: Long,
    val cost: Double,
    val settled: Boolean,
)

/**
 * 标的级别的持仓视图。
 *
 * **单 Lot 不变量**：[lot] 要么为 null（无持仓），要么唯一存在；
 * 任何破坏该不变量的代码路径都应在 [org.shiroumi.backtest.ledger.PositionLedger] 中抛出。
 */
@Serializable
data class StockPosition(
    val tsCode: String,
    val lot: Lot?,
) {
    /** 总持仓股数。 */
    val totalQty: Long get() = lot?.quantity ?: 0L

    /** 可卖股数：settled 的 Lot 才计入。 */
    val availableQty: Long get() = lot?.takeIf { it.settled }?.quantity ?: 0L

    /** T+1 锁仓股数：未 settled 的 Lot。 */
    val lockedTodayQty: Long get() = lot?.takeIf { !it.settled }?.quantity ?: 0L

    /** 平均成本（无持仓时为 0）。 */
    val avgCost: Double get() = lot?.cost ?: 0.0

    val isEmpty: Boolean get() = lot == null
}

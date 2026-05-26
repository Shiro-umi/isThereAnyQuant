package org.shiroumi.backtest.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 撮合成交回报。每个 [ValidatedOrder] 在 MatchingEngine 中至多产出一条 [Fill]
 * （日线撮合一次完成；分钟线场景下可能 partial fill，但本设计目前按一次成交处理）。
 *
 * 费用拆分三段以便审计：佣金、过户费、印花税（仅卖出）。
 */
@Serializable
data class Fill(
    val orderId: String,
    val tradeDate: LocalDate,
    val tsCode: String,
    val side: Side,
    /** 实际成交股数（≤ 委托数量）。 */
    val quantity: Long,
    /** 成交价（**RAW 原始价**）。 */
    val price: Double,
    /** 成交额（不含费用） = quantity × price。 */
    val grossAmount: Money,
    val commission: Money,
    val transferFee: Money,
    /** 印花税：仅卖出非零，买入恒为 0。 */
    val stampDuty: Money,
) {
    /** 现金净流：BUY 为负（流出），SELL 为正（流入），均已含费用扣减。 */
    val netCashFlow: Money
        get() = when (side) {
            Side.BUY -> -(grossAmount + commission + transferFee + stampDuty)
            Side.SELL -> grossAmount - commission - transferFee - stampDuty
        }

    /** 总费用合计（用于审计展示）。 */
    val totalFee: Money
        get() = commission + transferFee + stampDuty
}

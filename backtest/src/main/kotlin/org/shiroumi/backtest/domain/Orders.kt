package org.shiroumi.backtest.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 订单的三段式生命周期：
 *  1. [DraftOrder] —— OrderSizer 产出，**尚未通过规则校验**
 *  2. [ValidatedOrder] —— 已通过 RuleValidator 链路，可进入撮合
 *  3. [BlockedOrder] —— 在规则链上被否决，附带 [BlockReason] 与详情
 *
 * 设计意图：让"被否决"也是一等公民，便于审计与回归。
 */

@Serializable
data class DraftOrder(
    val orderId: String,
    val effectiveDate: LocalDate,
    val tsCode: String,
    val side: Side,
    val quantity: Long,
    /** 限价单的限价；OPEN/VWAP/CLOSE 等市价提示下可为空。 */
    val limitPrice: Double? = null,
    val hint: ExecutionHint = ExecutionHint.OPEN,
    /** 产生该订单的原因，例如「权重 0.20 首次建仓」或「权重清零，清仓」。 */
    val reason: String,
)

@Serializable
data class ValidatedOrder(
    val orderId: String,
    val effectiveDate: LocalDate,
    val tsCode: String,
    val side: Side,
    val quantity: Long,
    /** 校验链已把价格调整到价格档（0.01）；OPEN/VWAP/CLOSE 提示下为撮合时段的参考价。 */
    val limitPrice: Double,
    val hint: ExecutionHint,
)

@Serializable
data class BlockedOrder(
    val source: DraftOrder,
    val reason: BlockReason,
    val detail: String,
)

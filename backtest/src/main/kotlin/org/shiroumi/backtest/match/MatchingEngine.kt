package org.shiroumi.backtest.match

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.ValidatedOrder

/**
 * 撮合引擎：把已通过规则校验的订单转换为成交回报 [Fill]。
 *
 * 本类不修改账户，账户变更只能由下游 AccountLedger.apply(fills) 完成。
 */
class MatchingEngine(
    private val policy: MatchingPolicy,
    private val slippage: SlippageModel = SlippageModel(),
    private val costs: CostModel = CostModel(),
) {
    private val unfilled: MutableList<UnfilledOrder> = mutableListOf()

    fun unfilledSnapshot(): List<UnfilledOrder> = unfilled.toList()

    fun match(orders: List<ValidatedOrder>, ctx: MatchingContext): List<Fill> {
        val fills = mutableListOf<Fill>()
        for (order in orders) {
            val bar = ctx.bar(order.tsCode)
            if (bar == null) {
                unfilled += UnfilledOrder.from(order, ctx.tradeDate, "缺少当日 RAW 行情")
                continue
            }

            val price = policy.matchPrice(order, bar, slippage)
            if (price == null || !price.isFinite() || price <= 0.0) {
                unfilled += UnfilledOrder.from(order, ctx.tradeDate, "撮合条件未触发")
                continue
            }

            val gross = Money.ofTrade(price, order.quantity)
            fills += Fill(
                orderId = order.orderId,
                tradeDate = ctx.tradeDate,
                tsCode = order.tsCode,
                side = order.side,
                quantity = order.quantity,
                price = price,
                grossAmount = gross,
                commission = costs.commission(gross),
                transferFee = costs.transferFee(gross),
                stampDuty = costs.stampDuty(gross, order.side),
            )
        }
        return fills
    }
}

/** 已通过校验但未成交的订单记录，供后续审计层聚合。 */
data class UnfilledOrder(
    val orderId: String,
    val tradeDate: LocalDate,
    val tsCode: String,
    val reason: String,
) {
    companion object {
        fun from(order: ValidatedOrder, tradeDate: LocalDate, reason: String): UnfilledOrder =
            UnfilledOrder(order.orderId, tradeDate, order.tsCode, reason)
    }
}

package org.shiroumi.backtest.ledger

import kotlin.math.floor
import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.AuditReason
import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.BlockedOrder
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.LedgerView
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.output.TradeAudit

/**
 * 策略决策到草稿订单的翻译层。
 *
 * 本类只读 [LedgerView]：策略输出权重/方向，账户私有域负责把权重折成股数并执行
 * 1 买 1 卖、同日反向和现金缩放约束。
 */
class OrderSizer(
    private val costs: CostModelConfig = CostModelConfig(),
    private val defaultTradeIntentWeight: Double = 1.0,
) {

    fun size(decisions: List<StrategyDecision>, ledger: LedgerView, ctx: MatchingContext): SizingResult {
        val intents = expand(decisions, ledger)

        // 退出管理器生成的 SELL 与策略新 BUY 冲突时，退出优先：移除冲突的 BUY，保留 SELL
        val exitSellCodes = intents
            .filter { it.intendedSide == Side.SELL && isExitReason(it.reason) }
            .map { it.tsCode }
            .toSet()
        val resolvedIntents = if (exitSellCodes.isNotEmpty()) {
            intents.filter { intent ->
                !(intent.tsCode in exitSellCodes && intent.intendedSide == Side.BUY)
            }
        } else {
            intents
        }

        val reverseBlocks = sameDayReverseBlocks(resolvedIntents, ctx)
        val reversedTsCodes = reverseBlocks.map { it.source.tsCode }.toSet()

        val drafts = mutableListOf<DraftOrder>()
        val blocked = reverseBlocks.toMutableList()
        var index = 0

        for (intent in resolvedIntents) {
            if (intent.tsCode in reversedTsCodes) continue
            val currentQty = ledger.totalQty(intent.tsCode)
            when {
                intent.explicitSide == Side.BUY && currentQty > 0L -> {
                    blocked += blockedOrder(intent, ctx, BlockReason.ALREADY_HOLDING, "${intent.tsCode} 已持仓 $currentQty 股，禁止加仓")
                }
                currentQty == 0L && intent.targetWeight > 0.0 -> {
                    val price = ctx.bar(intent.tsCode)?.open?.toDouble() ?: continue
                    val quantity = buyQuantity(ledger.equity(priceMap(ctx)), intent.targetWeight, price)
                    if (quantity >= LOT) {
                        drafts += DraftOrder(
                            orderId = orderId(ctx, intent.tsCode, Side.BUY, index++),
                            effectiveDate = ctx.tradeDate,
                            tsCode = intent.tsCode,
                            side = Side.BUY,
                            quantity = quantity,
                            limitPrice = price,
                            hint = intent.hint,
                            reason = intent.reason,
                        )
                    }
                }
                currentQty > 0L && intent.targetWeight <= 0.0 -> {
                    // 退出管理器传来的限价单使用其指定价格，否则用开盘价
                    val price = if (intent.hint == ExecutionHint.LIMIT && intent.limitPrice != null) {
                        intent.limitPrice
                    } else {
                        ctx.bar(intent.tsCode)?.open?.toDouble()
                    }
                    drafts += DraftOrder(
                        orderId = orderId(ctx, intent.tsCode, Side.SELL, index++),
                        effectiveDate = ctx.tradeDate,
                        tsCode = intent.tsCode,
                        side = Side.SELL,
                        quantity = currentQty,
                        limitPrice = price,
                        hint = intent.hint,
                        reason = intent.reason,
                    )
                }
            }
        }

        val scaled = scaleBuysIfNeeded(drafts, ledger.cash, ctx)
        return SizingResult(
            draftOrders = scaled.orders,
            blockedOrders = blocked,
            audits = scaled.audits,
        )
    }

    private fun expand(decisions: List<StrategyDecision>, ledger: LedgerView): List<SizingIntent> {
        val result = mutableListOf<SizingIntent>()
        for (decision in decisions) {
            when (decision) {
                is StrategyDecision.TargetPortfolioDecision -> {
                    val targetTsCodes = decision.targetWeights.keys + ledger.positions.keys
                    for (tsCode in targetTsCodes) {
                        val weight = decision.targetWeights[tsCode] ?: 0.0
                        result += SizingIntent(
                            tsCode = tsCode,
                            targetWeight = weight.coerceIn(0.0, 1.0),
                            hint = ExecutionHint.OPEN,
                            reason = "${decision.reason}: targetWeight=$weight",
                            explicitSide = null,
                        )
                    }
                }
                is StrategyDecision.TradeIntentDecision -> {
                    val weight = when (decision.side) {
                        Side.BUY -> (decision.weight ?: defaultTradeIntentWeight).coerceIn(0.0, 1.0)
                        Side.SELL -> 0.0
                    }
                    result += SizingIntent(
                        tsCode = decision.tsCode,
                        targetWeight = weight,
                        hint = decision.hint,
                        reason = decision.reason,
                        explicitSide = decision.side,
                        limitPrice = decision.limitPrice,
                    )
                }
            }
        }
        return result
    }

    private fun sameDayReverseBlocks(intents: List<SizingIntent>, ctx: MatchingContext): List<BlockedOrder> =
        intents
            .groupBy { it.tsCode }
            .mapNotNull { (_, group) ->
                val sides = group.mapNotNull { it.intendedSide }.toSet()
                if (sides.size < 2) return@mapNotNull null
                blockedOrder(
                    group.first(),
                    ctx,
                    BlockReason.SAME_DAY_REVERSE,
                    "${group.first().tsCode} 同一交易日同时出现 BUY 与 SELL 意图",
                )
            }

    private fun blockedOrder(
        intent: SizingIntent,
        ctx: MatchingContext,
        reason: BlockReason,
        detail: String,
    ): BlockedOrder {
        val side = intent.intendedSide ?: intent.explicitSide ?: Side.BUY
        return BlockedOrder(
            source = DraftOrder(
                orderId = orderId(ctx, intent.tsCode, side, 0),
                effectiveDate = ctx.tradeDate,
                tsCode = intent.tsCode,
                side = side,
                quantity = 0L,
                limitPrice = ctx.bar(intent.tsCode)?.open?.toDouble(),
                hint = intent.hint,
                reason = intent.reason,
            ),
            reason = reason,
            detail = detail,
        )
    }

    private fun buyQuantity(equity: Money, weight: Double, price: Double): Long {
        if (price <= 0.0 || weight <= 0.0) return 0L
        val rawQty = equity.toDouble() * weight / price
        return floor(rawQty / LOT).toLong() * LOT
    }

    private fun scaleBuysIfNeeded(orders: List<DraftOrder>, cash: Money, ctx: MatchingContext): ScaledOrders {
        val required = requiredCash(orders)
        if (required <= cash || required.isZero) return ScaledOrders(orders, emptyList())

        val ratio = (cash.toDouble() / required.toDouble()).coerceIn(0.0, 1.0)
        val adjusted = orders.mapNotNull { order ->
            if (order.side != Side.BUY) return@mapNotNull order
            val scaledQty = floor(order.quantity * ratio / LOT).toLong() * LOT
            if (scaledQty >= LOT) order.copy(quantity = scaledQty, reason = "${order.reason}; cashScaled=$ratio") else null
        }.toMutableList()

        while (requiredCash(adjusted) > cash) {
            val largestBuyIndex = adjusted.withIndex()
                .filter { it.value.side == Side.BUY && it.value.quantity >= LOT }
                .maxByOrNull { it.value.quantity }
                ?.index ?: break
            val nextQty = adjusted[largestBuyIndex].quantity - LOT
            if (nextQty >= LOT) {
                adjusted[largestBuyIndex] = adjusted[largestBuyIndex].copy(quantity = nextQty)
            } else {
                adjusted.removeAt(largestBuyIndex)
            }
        }

        val audits = orders
            .filter { it.side == Side.BUY }
            .map {
                TradeAudit(
                    tradeDate = ctx.tradeDate,
                    tsCode = it.tsCode,
                    side = Side.BUY,
                    auditReason = AuditReason.CASH_SCALED,
                    detail = "${it.tsCode} 买入现金不足，按比例缩放订单数量",
                    scaleRatio = ratio,
                )
            }
        return ScaledOrders(adjusted, audits)
    }

    private fun requiredCash(orders: List<DraftOrder>): Money {
        var total = Money.ZERO
        for (order in orders) {
            if (order.side != Side.BUY) continue
            val price = order.limitPrice ?: continue
            val gross = Money.ofTrade(price, order.quantity)
            val commission = maxOf(gross * costs.commissionRate, costs.minCommission)
            val transferFee = gross * costs.transferFeeRate
            total += gross + commission + transferFee
        }
        return total
    }

    private fun priceMap(ctx: MatchingContext): Map<String, Double> =
        ctx.quotes.mapValues { (_, candle) -> candle.open.toDouble() }

    private fun orderId(ctx: MatchingContext, tsCode: String, side: Side, index: Int): String =
        "sizing-${ctx.tradeDate}-$tsCode-$side-$index"

    private data class SizingIntent(
        val tsCode: String,
        val targetWeight: Double,
        val hint: ExecutionHint,
        val reason: String,
        val explicitSide: Side?,
        val limitPrice: Double? = null,
    ) {
        val intendedSide: Side?
            get() = explicitSide ?: when {
                targetWeight > 0.0 -> Side.BUY
                targetWeight <= 0.0 -> Side.SELL
                else -> null
            }
    }

    private data class ScaledOrders(
        val orders: List<DraftOrder>,
        val audits: List<TradeAudit>,
    )

    private companion object {
        const val LOT = 100L

        /** 判定是否来自 [PositionExitManager] 的退出订单。 */
        fun isExitReason(reason: String): Boolean =
            reason.contains("止盈") || reason.contains("保盈阶梯") ||
                reason.contains("浅浮亏止损") || reason.contains("时间止损") ||
                reason.contains("价格止损")
    }
}

data class SizingResult(
    val draftOrders: List<DraftOrder>,
    val blockedOrders: List<BlockedOrder> = emptyList(),
    val audits: List<TradeAudit> = emptyList(),
)

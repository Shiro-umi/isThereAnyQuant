package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side

/**
 * 流动性约束规则。
 *
 *  - [fractionLimit] = null：不做限制（默认）
 *  - [fractionLimit] = 0.05：单标的委托数量按当日成交量 × 5% 截断
 *
 * 计算口径：当日 [model.Candle.volume] 是"手数"还是"股数"取决于数据源；
 * 项目内 Candle.volume 单位已知为"股"（与 ts_code 表同源），因此直接与 quantity 对比。
 *
 * 行情缺失（拿不到 bar）的情况由 [TradabilityRule] 阻断，这里不重复判断。
 */
class LiquidityRule(private val fractionLimit: Double?) : MarketRule {
    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        val limit = fractionLimit ?: return RuleOutcome.Pass(order)
        val bar = ctx.bar(order.tsCode) ?: return RuleOutcome.Pass(order)
        val dailyVolume = bar.volume.toLong()
        if (dailyVolume <= 0L) {
            return RuleOutcome.Block(
                BlockReason.LIQUIDITY_EXHAUSTED,
                "${order.tsCode} 当日成交量为 0，无法撮合 ${order.quantity} 股",
            )
        }
        val rawCap = (dailyVolume * limit).toLong()
        val cap = when (order.side) {
            Side.BUY -> (rawCap / LOT) * LOT
            Side.SELL -> rawCap
        }
        if (cap <= 0L) {
            return RuleOutcome.Block(
                BlockReason.LIQUIDITY_EXHAUSTED,
                "${order.tsCode} 当日成交量 $dailyVolume 的 ${(limit * 100).toInt()}% 不足以形成可成交数量",
            )
        }
        if (order.quantity <= cap) return RuleOutcome.Pass(order)

        if (order.side == Side.SELL && order.quantity == ctx.ledger.totalQty(order.tsCode)) {
            return RuleOutcome.Block(
                BlockReason.LIQUIDITY_EXHAUSTED,
                "${order.tsCode} 清仓单 ${order.quantity} 股超出当日流动性上限 $cap；1 买 1 卖语义下不做部分卖出",
            )
        }

        return RuleOutcome.Pass(
            order.copy(
                quantity = cap,
                reason = "${order.reason}; liquidityCapped=$cap",
            )
        )
    }

    private companion object {
        const val LOT = 100L
    }
}

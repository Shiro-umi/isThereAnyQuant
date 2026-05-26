package org.shiroumi.backtest.rule

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 涨跌停规则。
 *
 *  - 主板 ±[mainBoardLimit]（默认 0.10）
 *  - 创业板 / 科创板 / 北交所 ±[growthBoardLimit]（默认 0.20）
 *  - 涨跌停价 = preClose × (1 ± limit)，按四舍五入到 0.01 元
 *
 * 触发条件（与设计文档 §2.3 对齐）：
 *  - BUY：参考价 ≥ 涨停价 → [BlockReason.LIMIT_UP_BUY]
 *  - SELL：参考价 ≤ 跌停价 → [BlockReason.LIMIT_DOWN_SELL]
 *
 * 参考价选择：
 *  - 限价单：取订单 limitPrice
 *  - 市价类（OPEN/VWAP/CLOSE）：按 [MatchingContext.bar] 对应的开盘价
 *  - 若仍取不到价格，则按"参考价不可得"视为通过（由 MatchingEngine 兜底——但实务中不会出现，
 *    因为 TradabilityRule 已经过滤无行情的标的）
 *
 * 若拿不到 preClose（典型如新股第 1 个交易日），则规则放行——靠 [TradabilityRule] 的
 * IPO 冻结配置去做隔离，而不是在这里做硬阻断（避免数据缺失导致误判）。
 */
class PriceLimitRule(
    private val mainBoardLimit: Double = 0.10,
    private val growthBoardLimit: Double = 0.20,
) : MarketRule {

    override fun apply(order: DraftOrder, ctx: MatchingContext): RuleOutcome {
        val preClose = ctx.preCloseOf(order.tsCode) ?: return RuleOutcome.Pass(order)
        val limitPct = limitFor(order.tsCode)
        val upper = round2(preClose * (1.0 + limitPct))
        val lower = round2(preClose * (1.0 - limitPct))

        val refPrice = order.limitPrice ?: ctx.bar(order.tsCode)?.open?.toDouble()
        ?: return RuleOutcome.Pass(order)

        return when (order.side) {
            Side.BUY -> if (refPrice + EPS >= upper) {
                RuleOutcome.Block(
                    BlockReason.LIMIT_UP_BUY,
                    "${order.tsCode} 涨停价 $upper，参考价 $refPrice，买单无法成交",
                )
            } else RuleOutcome.Pass(order)

            Side.SELL -> if (refPrice - EPS <= lower) {
                RuleOutcome.Block(
                    BlockReason.LIMIT_DOWN_SELL,
                    "${order.tsCode} 跌停价 $lower，参考价 $refPrice，卖单无法成交",
                )
            } else RuleOutcome.Pass(order)
        }
    }

    private fun limitFor(tsCode: String): Double {
        val code = tsCode.substringBefore(".")
        val market = tsCode.substringAfter(".", missingDelimiterValue = "")
        // 创业板 300/301、科创板 688、北交所均按"成长板"宽幅处理。
        // 其它（沪市 60x、深市 000/001/002/003）按主板。
        return when {
            market.equals("BJ", ignoreCase = true) -> growthBoardLimit
            code.startsWith("688") -> growthBoardLimit
            code.startsWith("300") -> growthBoardLimit
            code.startsWith("301") -> growthBoardLimit
            else -> mainBoardLimit
        }
    }

    private fun round2(v: Double): Double =
        BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    private companion object {
        /** 价格比较容差：避免浮点累积误差让"贴近涨停"被错误放行。 */
        const val EPS = 1e-6
    }
}

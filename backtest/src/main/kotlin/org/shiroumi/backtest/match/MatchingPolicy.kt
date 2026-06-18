package org.shiroumi.backtest.match

import model.Candle
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.ValidatedOrder

/**
 * 撮合定价策略：只决定一笔已校验订单的 RAW 参考成交价。
 *
 * 规则校验已在上游完成，策略实现不再判断停牌、涨跌停、现金或 T+1。
 */
fun interface MatchingPolicy {
    fun matchPrice(order: ValidatedOrder, bar: Candle, slippage: SlippageModel): Double?
}

/** 日线默认：按下一交易日开盘价成交。 */
object OpenPriceMatching : MatchingPolicy {
    override fun matchPrice(order: ValidatedOrder, bar: Candle, slippage: SlippageModel): Double =
        slippage.apply(bar.open.toDouble(), order.side)
}

/** 当日 VWAP 近似：Candle 暂无 VWAP 字段，使用 OHLC 均价近似。 */
object VwapMatching : MatchingPolicy {
    override fun matchPrice(order: ValidatedOrder, bar: Candle, slippage: SlippageModel): Double {
        val approximateVwap = (
            bar.open.toDouble() +
                bar.high.toDouble() +
                bar.low.toDouble() +
                bar.close.toDouble()
            ) / 4.0
        return slippage.apply(approximateVwap, order.side)
    }
}

/** 因子研究常用口径：按收盘价成交，实盘含义偏理想。 */
object ClosePriceMatching : MatchingPolicy {
    override fun matchPrice(order: ValidatedOrder, bar: Candle, slippage: SlippageModel): Double =
        slippage.apply(bar.close.toDouble(), order.side)
}

/**
 * 限价单撮合：日线级只能判断当日价格区间是否穿越限价。
 *
 * BUY：当日最低价触达限价才成交；SELL：当日最高价触达限价才成交。
 * 成交价不突破限价约束，滑点只在限价允许范围内生效。
 */
object LimitOrderMatching : MatchingPolicy {
    /**
     * 价格触达判定容差。行情价为分（0.01）级，limit 与 low/high 经 QFQ 换算后是 Float，
     * `low == limit` 的精确触及会因 Float 二进制表示误差被误判为「未触及」（如 low_qfq=7.01 的 Float
     * 实际略大于 7.01 → 7.01>7.01 成立 → 漏单）。用 1e-4（远小于最小报价单位 0.01）吸收换算误差，
     * 不会把真正未触及（差 ≥0.01）的单纳入成交。
     */
    private const val TOUCH_EPS = 1e-4

    override fun matchPrice(order: ValidatedOrder, bar: Candle, slippage: SlippageModel): Double? {
        val limit = order.limitPrice
        return when (order.side) {
            Side.BUY -> {
                if (bar.low.toDouble() > limit + TOUCH_EPS) return null
                val reference = minOf(bar.open.toDouble(), limit)
                minOf(slippage.apply(reference, order.side), limit)
            }
            Side.SELL -> {
                if (bar.high.toDouble() < limit - TOUCH_EPS) return null
                val reference = maxOf(bar.open.toDouble(), limit)
                maxOf(slippage.apply(reference, order.side), limit)
            }
        }
    }
}

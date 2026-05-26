package org.shiroumi.backtest.match

import org.shiroumi.backtest.config.SlippageConfig
import org.shiroumi.backtest.domain.Side
import kotlin.math.max

/**
 * 基点制滑点模型。
 *
 * BUY 向上吃价，SELL 向下让价；本类只处理撮合价，不参与涨跌停和价格档校验。
 */
class SlippageModel(config: SlippageConfig = SlippageConfig()) {
    private val rate: Double = max(0, config.basisPoints) / 10_000.0

    fun apply(price: Double, side: Side): Double {
        require(price.isFinite() && price > 0.0) { "滑点参考价必须为正数" }
        return when (side) {
            Side.BUY -> price * (1.0 + rate)
            Side.SELL -> price * (1.0 - rate)
        }
    }
}

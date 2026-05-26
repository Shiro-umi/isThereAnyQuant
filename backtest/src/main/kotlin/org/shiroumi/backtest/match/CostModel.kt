package org.shiroumi.backtest.match

import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side

/**
 * A 股成交费用模型。
 *
 * 买入：佣金 + 过户费；卖出：佣金 + 过户费 + 印花税。
 * 参数来自 [CostModelConfig]，便于后续按券商或政策调整。
 */
class CostModel(private val config: CostModelConfig = CostModelConfig()) {

    fun commission(amount: Money): Money = maxOf(amount * config.commissionRate, config.minCommission)

    fun transferFee(amount: Money): Money = amount * config.transferFeeRate

    fun stampDuty(amount: Money, side: Side): Money = when (side) {
        Side.BUY -> Money.ZERO
        Side.SELL -> amount * config.stampDutyRate
    }
}

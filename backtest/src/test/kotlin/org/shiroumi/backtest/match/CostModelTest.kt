package org.shiroumi.backtest.match

import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class CostModelTest {

    private val model = CostModel()

    @Test fun `小额成交佣金按最低 5 元收取`() {
        assertEquals(Money.ofYuan(5.0), model.commission(Money.ofYuan(1_000.0)))
    }

    @Test fun `过户费按成交额费率计算`() {
        assertEquals(Money.ofYuan(0.01), model.transferFee(Money.ofYuan(1_000.0)))
    }

    @Test fun `印花税只在卖出侧收取`() {
        assertEquals(Money.ZERO, model.stampDuty(Money.ofYuan(1_000.0), Side.BUY))
        assertEquals(Money.ofYuan(0.5), model.stampDuty(Money.ofYuan(1_000.0), Side.SELL))
    }
}

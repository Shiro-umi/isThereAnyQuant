package org.shiroumi.backtest.output

import org.shiroumi.backtest.engine.DailyRunRecord
import org.shiroumi.backtest.engine.DailyRunStatus
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationStateStreamTest {

    @Test fun `逐日记录转换为 DailyState`() {
        val states = SimulationStateStream(
            listOf(DailyRunRecord(tradeDate = T1, status = DailyRunStatus.COMPLETED))
        ).states()

        assertEquals(T1, states.single().tradeDate)
        assertEquals(DailyRunStatus.COMPLETED, states.single().status)
    }
}

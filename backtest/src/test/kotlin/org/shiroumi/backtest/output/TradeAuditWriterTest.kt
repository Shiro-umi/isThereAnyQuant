package org.shiroumi.backtest.output

import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.BlockedOrder
import org.shiroumi.backtest.engine.DailyRunRecord
import org.shiroumi.backtest.engine.DailyRunStatus
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.order
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeAuditWriterTest {

    @Test fun `把 BlockedOrder 转成 TradeAudit`() {
        val day = DailyRunRecord(
            tradeDate = T1,
            status = DailyRunStatus.COMPLETED,
            blockedOrders = listOf(
                BlockedOrder(
                    source = order(),
                    reason = BlockReason.SUSPENDED,
                    detail = "停牌",
                )
            ),
        )

        val audit = TradeAuditWriter.auditsFor(day).single()

        assertEquals(BlockReason.SUSPENDED, audit.reason)
        assertEquals("停牌", audit.detail)
    }
}

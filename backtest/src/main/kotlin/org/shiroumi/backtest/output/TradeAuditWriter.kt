package org.shiroumi.backtest.output

import org.shiroumi.backtest.engine.DailyRunRecord

/**
 * 把 sizing 调整和规则阻断统一整理为交易审计。
 */
object TradeAuditWriter {
    fun auditsFor(day: DailyRunRecord): List<TradeAudit> {
        val blockedAudits = day.blockedOrders.map { blocked ->
            TradeAudit(
                tradeDate = day.tradeDate,
                tsCode = blocked.source.tsCode,
                side = blocked.source.side,
                reason = blocked.reason,
                detail = blocked.detail,
            )
        }
        return day.audits + blockedAudits
    }
}

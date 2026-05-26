package org.shiroumi.backtest.output

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.BlockedOrder
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.engine.DailyRunRecord
import org.shiroumi.backtest.engine.DailyRunStatus

/**
 * 逐日状态流，把 Scheduler 的执行记录整理成输出层稳定模型。
 */
class SimulationStateStream(private val days: List<DailyRunRecord>) {
    fun states(): List<DailyState> = days.map { day ->
        DailyState(
            tradeDate = day.tradeDate,
            status = day.status,
            draftOrders = day.draftOrders,
            blockedOrders = day.blockedOrders,
            fills = day.fills,
            cashFlows = emptyList(),
            equityPoint = day.settlement?.equityPoint,
            error = day.error,
        )
    }
}

data class DailyState(
    val tradeDate: LocalDate,
    val status: DailyRunStatus,
    val draftOrders: List<DraftOrder>,
    val blockedOrders: List<BlockedOrder>,
    val fills: List<Fill>,
    val cashFlows: List<CashFlow>,
    val equityPoint: EquityPoint?,
    val error: String?,
)

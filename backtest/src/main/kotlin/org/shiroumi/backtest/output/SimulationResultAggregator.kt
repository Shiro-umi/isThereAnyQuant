package org.shiroumi.backtest.output

import org.shiroumi.backtest.engine.SchedulerRunResult

/**
 * 把 Scheduler 输出聚合为最终 SimulationResult。
 */
class SimulationResultAggregator(
    private val performanceReporter: PerformanceReporter = PerformanceReporter(),
) {
    fun aggregate(runId: String, result: SchedulerRunResult): SimulationResult {
        val fills = result.days.flatMap { it.fills }
        val equityCurve = result.days.mapNotNull { it.settlement?.equityPoint }
        val positions = result.days.mapNotNull { it.settlement?.positionSnapshot }
        val cashFlows = result.ledger.cashFlowSnapshot()
        val audits = result.days.flatMap { TradeAuditWriter.auditsFor(it) }
        val lotContributions = performanceReporter.lotContributions(fills)
        return SimulationResult(
            runId = runId,
            orders = orderRecords(result),
            positions = positions,
            cashFlows = cashFlows,
            equityCurve = equityCurve,
            audits = audits,
            lotContributions = lotContributions,
            metrics = performanceReporter.metrics(
                initialCapital = result.initialCapital,
                equityCurve = equityCurve,
                fills = fills,
            ),
        )
    }

    private fun orderRecords(result: SchedulerRunResult): List<OrderRecord> {
        val records = mutableListOf<OrderRecord>()
        for (day in result.days) {
            val fillsByOrderId = day.fills.associateBy { it.orderId }
            val blockedByOrderId = day.blockedOrders.associateBy { it.source.orderId }
            for (draft in day.draftOrders) {
                val blocked = blockedByOrderId[draft.orderId]
                records += OrderRecord(
                    draft = draft,
                    fill = fillsByOrderId[draft.orderId],
                    blockedReason = blocked?.reason,
                    blockedDetail = blocked?.detail,
                )
            }
            val draftIds = day.draftOrders.map { it.orderId }.toSet()
            for (blocked in day.blockedOrders) {
                if (blocked.source.orderId !in draftIds) {
                    records += OrderRecord(
                        draft = blocked.source,
                        blockedReason = blocked.reason,
                        blockedDetail = blocked.detail,
                    )
                }
            }
        }
        return records
    }
}

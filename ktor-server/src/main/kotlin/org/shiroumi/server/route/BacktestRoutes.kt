package org.shiroumi.server.route

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.config.OutputConfig
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.engine.BacktestRunExecutor
import org.shiroumi.backtest.output.EquityPoint
import org.shiroumi.backtest.output.SimulationResult
import org.shiroumi.server.dto.ApiResponse

@Serializable
data class BacktestRunRequest(
    val start: String,
    val end: String,
    val decisions: String = "audit",
    val capital: Double = 1_000_000.0,
    val equityCurveCsv: String? = null,
)

@Serializable
data class BacktestRunResponse(
    val runId: String,
    val status: String,
    val metrics: BacktestMetricsResponse,
    val equity: List<BacktestEquityResponse>,
    val orderCount: Int,
    val auditCount: Int,
)

@Serializable
data class BacktestMetricsResponse(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val sortino: Double,
    val winRate: Double,
    val turnover: Double,
    val avgHoldingDays: Double,
)

@Serializable
data class BacktestEquityResponse(
    val tradeDate: String,
    val cash: Double,
    val positionValue: Double,
    val equity: Double,
    val drawdown: Double,
)

fun Route.backtestRoutes() {
    route("/api/backtest") {
        post("/run") {
            val request = call.receive<BacktestRunRequest>()
            val config = request.toConfig()
            val executor = BacktestRunExecutor()
            val runId = executor.createRunId()
            val result = executor.runSimulation(runId, config, request.decisions)
            executor.exportEquityCurveIfNeeded(runId, config, result)
            call.respond(ApiResponse.success(result.toResponse(runId)))
        }
    }
}

private fun BacktestRunRequest.toConfig(): BacktestConfig =
    BacktestConfig(
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        initialCapital = Money.ofYuan(capital),
        output = OutputConfig(equityCurveCsv = equityCurveCsv),
    )

private fun SimulationResult.toResponse(runId: String): BacktestRunResponse =
    BacktestRunResponse(
        runId = runId,
        status = "SUCCEEDED",
        metrics = BacktestMetricsResponse(
            totalReturn = metrics.totalReturn,
            annualizedReturn = metrics.annualizedReturn,
            maxDrawdown = metrics.maxDrawdown,
            sharpe = metrics.sharpe,
            sortino = metrics.sortino,
            winRate = metrics.winRate,
            turnover = metrics.turnover,
            avgHoldingDays = metrics.avgHoldingDays,
        ),
        equity = equityCurve.toEquityResponse(),
        orderCount = orders.size,
        auditCount = audits.size,
    )

private fun List<EquityPoint>.toEquityResponse(): List<BacktestEquityResponse> {
    var peak = firstOrNull()?.equity?.toDouble() ?: 0.0
    return map { point ->
        val equity = point.equity.toDouble()
        if (equity > peak) peak = equity
        BacktestEquityResponse(
            tradeDate = point.tradeDate.toString(),
            cash = point.cash.toDouble(),
            positionValue = point.positionValue.toDouble(),
            equity = equity,
            drawdown = if (peak > 0.0) (peak - equity) / peak else 0.0,
        )
    }
}

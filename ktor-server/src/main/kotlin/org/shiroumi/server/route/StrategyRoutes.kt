package org.shiroumi.server.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyTargetPortfolioRepository
import org.shiroumi.server.dto.ApiResponse

@kotlinx.serialization.Serializable
data class StrategySentimentResponse(
    val tradeDate: String,
    val sentimentExposure: Double,
    val bullRatio: Double,
    val marketVol: Double,
    val fftScore: Double,
    val residualScore: Double,
    val accelZ: Double,
    val volZ: Double,
    val selectedCount: Int,
    val emptyReason: String?,
    val ratioNorm: Double,
    val volScore: Double,
    val accelScore: Double,
    val absoluteFloor: Double,
    val volCap: Double,
)

@kotlinx.serialization.Serializable
data class StrategyPositionResponse(
    val tradeDate: String,
    val currentPositions: List<String>,
    val newlySelected: List<String>,
    val dropped: List<String>,
    val nextSessionSelections: List<String>,
)

fun Route.strategyRoutes() {
    route("/strategy") {

        // 获取最近的情绪指标曲线（默认近 60 天）
        get("/sentiment") {
            try {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 60
                val records = DailyStrategyAuditRepository.getRecentRecords(limit)
                val data = records.map { summary ->
                    StrategySentimentResponse(
                        tradeDate = summary.tradeDate.toString(),
                        sentimentExposure = summary.sentimentExposure,
                        bullRatio = summary.bullRatio,
                        marketVol = summary.marketVol,
                        fftScore = summary.fftScore,
                        residualScore = summary.residualScore,
                        accelZ = summary.accelZ,
                        volZ = summary.volZ,
                        selectedCount = summary.selectedCount,
                        emptyReason = summary.emptyReason,
                        ratioNorm = summary.ratioNorm,
                        volScore = summary.volScore,
                        accelScore = summary.accelScore,
                        absoluteFloor = summary.absoluteFloor,
                        volCap = summary.volCap,
                    )
                }.reversed() // 返回按时间正序的列表，方便图表绘制

                call.respond(ApiResponse.success(data))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse.error<List<StrategySentimentResponse>>("INTERNAL_ERROR", e.message ?: "服务器内部错误")
                )
            }
        }

        // 获取最新一天的轻量策略状态。行情页主链路使用 STRATEGY_POSITIONS WebSocket。
        get("/positions") {
            try {
                val records = DailyStrategyAuditRepository.getRecentRecords(1)
                val summary = records.firstOrNull()
                if (summary == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error<StrategyPositionResponse>("NOT_FOUND", "暂无策略持仓数据"))
                } else {
                    val nextSessionSelections = DailyTargetPortfolioRepository.findSelectionsByTradeDates(listOf(summary.tradeDate))
                        .getOrElse(summary.tradeDate) { emptyList() }
                        .map { it.tsCode }
                    val data = StrategyPositionResponse(
                        tradeDate = summary.tradeDate.toString(),
                        currentPositions = summary.currentPositions,
                        newlySelected = nextSessionSelections.filterNot { it in summary.currentPositions },
                        dropped = summary.dropped,
                        nextSessionSelections = nextSessionSelections,
                    )
                    call.respond(ApiResponse.success(data))
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse.error<StrategyPositionResponse>("INTERNAL_ERROR", e.message ?: "服务器内部错误")
                )
            }
        }
    }
}

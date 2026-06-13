package org.shiroumi.server.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import model.candle.StrategyPositionTrackingResponse
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.server.dto.ApiResponse
import org.shiroumi.server.runtime.strategy.StrategyRuntimeBridge

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
    val nextSessionSelectionDetails: List<StrategySelectionResponse> = emptyList(),
)

@kotlinx.serialization.Serializable
data class StrategySelectionResponse(
    val tsCode: String,
    val modelScore: Double,
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

        // 最早跟随日校准：以 followStartDate 空仓起步重放生产持仓规则，
        // 返回跟随者视角的持仓跟踪流（strategy-service 按需计算，主链路仍走 STRATEGY_POSITION_TRACKING WebSocket）
        get("/tracking/calibrated") {
            val raw = call.request.queryParameters["followStartDate"]
            val followStartDate = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (followStartDate == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<StrategyPositionTrackingResponse>(
                        "INVALID_PARAM",
                        "followStartDate 必须为 yyyy-MM-dd 格式的交易日"
                    )
                )
                return@get
            }
            StrategyRuntimeBridge.buildCalibratedTracking(followStartDate).fold(
                onSuccess = { call.respond(ApiResponse.success(it)) },
                onFailure = { error ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiResponse.error<StrategyPositionTrackingResponse>(
                            "CALIBRATION_UNAVAILABLE",
                            error.message ?: "策略持仓校准重放当前不可用"
                        )
                    )
                }
            )
        }

        // 获取最新一天的轻量策略状态。行情页主链路使用 STRATEGY_POSITIONS WebSocket。
        get("/positions") {
            try {
                val records = DailyStrategyAuditRepository.getRecentRecords(1)
                val summary = records.firstOrNull()
                if (summary == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error<StrategyPositionResponse>("NOT_FOUND", "暂无策略持仓数据"))
                } else {
                    val nextSessionSelectionRecords = DailyProfitPredictionSelectionRepository.findSelectionsByTradeDates(listOf(summary.tradeDate))
                        .getOrElse(summary.tradeDate) { emptyList() }
                    val nextSessionSelections = nextSessionSelectionRecords.map { it.tsCode }
                    val data = StrategyPositionResponse(
                        tradeDate = summary.tradeDate.toString(),
                        currentPositions = summary.currentPositions,
                        newlySelected = nextSessionSelections.filterNot { it in summary.currentPositions },
                        dropped = summary.dropped,
                        nextSessionSelections = nextSessionSelections,
                        nextSessionSelectionDetails = nextSessionSelectionRecords.map {
                            StrategySelectionResponse(tsCode = it.tsCode, modelScore = it.modelScore)
                        },
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

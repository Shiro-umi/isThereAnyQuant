package org.shiroumi.quant_kmp.feature.strategytracking.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import model.ApiResponse
import model.candle.StrategyPositionTrackingResponse

/**
 * 策略持仓跟踪 REST 数据源。主链路（模型自身持仓流）走 STRATEGY_POSITION_TRACKING WebSocket；
 * 此 Repository 承载按需计算的最早跟随日校准视图。
 */
class StrategyTrackingRepository(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {

    /**
     * 最早跟随日校准：服务端以 [followStartDate]（第一笔跟随买入日）空仓起步
     * 重放生产持仓规则，返回跟随者视角的持仓跟踪流。
     */
    suspend fun getCalibratedTracking(followStartDate: String): Result<StrategyPositionTrackingResponse> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/strategy/tracking/calibrated") {
                parameter("followStartDate", followStartDate)
            }
            val apiResponse: ApiResponse<StrategyPositionTrackingResponse> = response.body()
            val data = apiResponse.data
            if (apiResponse.success && data != null) {
                Result.success(data)
            } else {
                Result.failure(Exception(apiResponse.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

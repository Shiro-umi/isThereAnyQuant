package org.shiroumi.quant_kmp.data.candle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.datetime.LocalDate
import model.ApiResponse
import model.candle.CandleChartData
import model.candle.Exchange
import model.candle.StockInfo
import model.candle.StockListResponse
import model.candle.StrategySentimentResponse

/**
 * CandleRepository 实现类
 * 通过 HTTP 客户端与后端 API 通信获取蜡烛图数据
 */
class CandleRepositoryImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : CandleRepository {

    override suspend fun getStocks(
        page: Int,
        pageSize: Int,
        exchange: Exchange?,
        search: String?
    ): Result<StockListResponse> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/stocks") {
                parameter("page", page)
                parameter("pageSize", pageSize)
                parameter("sortBy", "RANK_SCORE")
                parameter("sortOrder", "DESC")
                exchange?.let { parameter("exchange", it.name) }
                search?.takeIf { it.isNotBlank() }?.let { parameter("search", it) }
            }
            val apiResponse: ApiResponse<StockListResponse> = response.body()
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

    override suspend fun searchStocks(query: String): Result<List<StockInfo>> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/stocks/search") {
                parameter("q", query)
            }
            val apiResponse: ApiResponse<StockSearchResponse> = response.body()
            val data = apiResponse.data
            if (apiResponse.success && data != null) {
                Result.success(data.stocks)
            } else {
                Result.failure(Exception(apiResponse.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getStockByCode(code: String): Result<StockInfo> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/stocks/$code")
            val apiResponse: ApiResponse<StockInfo> = response.body()
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

    override suspend fun getStrategySentiment(limit: Int): Result<List<StrategySentimentResponse>> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/strategy/sentiment") {
                parameter("limit", limit)
            }
            val apiResponse: ApiResponse<List<StrategySentimentResponse>> = response.body()
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

    override suspend fun getStockCandles(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<CandleChartData> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/stocks/$code/candles") {
                parameter("startDate", startDate.toString())
                parameter("endDate", endDate.toString())
            }
            val apiResponse: ApiResponse<CandleChartData> = response.body()
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

/**
 * 股票搜索响应（内部使用）
 */
@kotlinx.serialization.Serializable
private data class StockSearchResponse(
    val stocks: List<StockInfo>
)

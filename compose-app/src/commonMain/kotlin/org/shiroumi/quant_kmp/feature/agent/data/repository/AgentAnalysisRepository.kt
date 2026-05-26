package org.shiroumi.quant_kmp.feature.agent.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import model.ApiResponse
import model.agent.AgentAnalysisResultDto
import model.agent.CreateShareRequest
import model.agent.CreateShareResponse
import model.agent.ShareStatsDto
import org.shiroumi.config.AppConfig

/**
 * Agent 分析结果仓库
 *
 * 封装与后端 /api/v1/agent/analysis 的 HTTP 交互。
 */
class AgentAnalysisRepository(
    private val httpClient: HttpClient
) {
    private val baseUrl = AppConfig.apiBaseUrl

    /**
     * 查询当前用户的分析历史列表
     */
    suspend fun getAnalysisResults(
        tsCode: String? = null,
        type: String? = null,
        limit: Int = 50
    ): Result<List<AgentAnalysisResultDto>> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/agent/analysis") {
                parameter("tsCode", tsCode)
                parameter("type", type)
                parameter("limit", limit)
            }
            val apiResponse: ApiResponse<List<AgentAnalysisResultDto>> = response.body()
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

    /**
     * 获取单条分析详情
     */
    suspend fun getAnalysisResultById(id: String): Result<AgentAnalysisResultDto> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/agent/analysis/$id")
            val apiResponse: ApiResponse<AgentAnalysisResultDto> = response.body()
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

    /**
     * 生成（或返回已有的）分享链接。幂等。
     */
    suspend fun createShareLink(
        id: String,
        themeName: String? = null,
        isDark: Boolean? = null,
    ): Result<CreateShareResponse> {
        return try {
            val response = httpClient.post("$baseUrl/api/v1/agent/analysis/$id/share") {
                contentType(ContentType.Application.Json)
                setBody(CreateShareRequest(themeName = themeName, isDark = isDark))
            }
            val apiResponse: ApiResponse<CreateShareResponse> = response.body()
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

    /**
     * 查询分享访问统计；未分享时 shareToken 为 null。
     */
    suspend fun getShareStats(id: String): Result<ShareStatsDto> {
        return try {
            val response = httpClient.get("$baseUrl/api/v1/agent/analysis/$id/share/stats")
            val apiResponse: ApiResponse<ShareStatsDto> = response.body()
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

    /**
     * 删除单条分析记录
     */
    suspend fun deleteAnalysisResult(id: String): Result<Unit> {
        return try {
            val response = httpClient.delete("$baseUrl/api/v1/agent/analysis/$id")
            if (response.status.value in 200..299) {
                Result.success(Unit)
            } else {
                val apiResponse: ApiResponse<String> = response.body()
                Result.failure(Exception(apiResponse.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

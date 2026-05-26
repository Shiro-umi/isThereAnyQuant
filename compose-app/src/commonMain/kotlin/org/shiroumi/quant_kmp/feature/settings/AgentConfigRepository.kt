package org.shiroumi.quant_kmp.feature.settings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import model.ApiResponse
import model.agent.UpdateUserAgentConfigRequest
import model.agent.UserAgentConfigDto
import org.shiroumi.config.AppConfig

class AgentConfigRepository(
    private val httpClient: HttpClient
) {
    private val baseUrl = AppConfig.apiBaseUrl

    suspend fun load(): Result<UserAgentConfigDto> = runCatching {
        val response = httpClient.get("$baseUrl/api/v1/agent/config")
        val apiResponse: ApiResponse<UserAgentConfigDto> = response.body()
        val data = apiResponse.data
        if (apiResponse.success && data != null) {
            data
        } else {
            error(apiResponse.message)
        }
    }

    suspend fun save(request: UpdateUserAgentConfigRequest): Result<UserAgentConfigDto> = runCatching {
        val response = httpClient.put("$baseUrl/api/v1/agent/config") {
            setBody(request)
        }
        val apiResponse: ApiResponse<UserAgentConfigDto> = response.body()
        val data = apiResponse.data
        if (apiResponse.success && data != null) {
            data
        } else {
            error(apiResponse.message)
        }
    }
}

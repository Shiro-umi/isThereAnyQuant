package org.shiroumi.server.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import ktor.auth.getCurrentUserId
import model.agent.AgentModelSelectionMode
import model.agent.UpdateUserAgentConfigRequest
import model.agent.UserAgentConfigDto
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.user.createUserAgentConfigRepository
import org.shiroumi.server.agent.AgentModelConfigResolver
import org.shiroumi.server.dto.ApiResponse

fun Route.agentConfigRoutes() {
    authenticate("auth-jwt") {
        route("/agent/config") {
            get {
                val userId = call.getCurrentUserId()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )
                val quantConfig = ConfigManager.getConfig()
                val repository = createUserAgentConfigRepository()
                val userConfig = repository.findByUserId(userId)
                val effective = AgentModelConfigResolver.resolve(quantConfig, userConfig)
                val customKey = AgentModelConfigResolver.decryptApiKey(
                    userConfig?.customApiKeyEncrypted.orEmpty(),
                    quantConfig
                )
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        data = UserAgentConfigDto(
                            presets = AgentModelConfigResolver.presets(quantConfig.agent),
                            defaultPresetKey = AgentModelConfigResolver.defaultPresetKey(quantConfig.agent),
                            selectedMode = effective.selectionMode,
                            selectedPresetKey = effective.presetKey,
                            customDisplayName = userConfig?.customModelDisplayName,
                            customModelId = userConfig?.customModelId,
                            customBaseUrl = userConfig?.customBaseUrl,
                            hasCustomApiKey = !customKey.isNullOrBlank(),
                            maskedCustomApiKey = AgentModelConfigResolver.maskApiKey(customKey),
                            runtimeModelLabel = effective.displayName,
                            pendingRestart = org.shiroumi.server.websocket.service.AgentWebSocketService.hasRuntimeConfigDrift(
                                userId = userId,
                                modelId = effective.modelId,
                                baseUrl = effective.baseUrl,
                                apiKey = effective.apiKey,
                                provider = effective.provider
                            )
                        )
                    )
                )
            }

            put {
                val userId = call.getCurrentUserId()
                    ?: return@put call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )
                val request = call.receive<UpdateUserAgentConfigRequest>()
                val quantConfig = ConfigManager.getConfig()
                val presets = AgentModelConfigResolver.presets(quantConfig.agent).associateBy { it.key }

                val repository = createUserAgentConfigRepository()
                val current = repository.findByUserId(userId)

                if (request.selectedMode == AgentModelSelectionMode.PRESET) {
                    val presetKey = request.selectedPresetKey
                    if (presetKey.isNullOrBlank() || presetKey !in presets) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<String>("INVALID_MODEL_PRESET", "请选择有效的预设模型")
                        )
                    }
                } else {
                    if (request.customModelId.isNullOrBlank() || request.customBaseUrl.isNullOrBlank()) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<String>("INVALID_CUSTOM_MODEL", "自定义模型需要填写 Model ID 和 Base URL")
                        )
                    }
                    val hasNewKey = !request.customApiKey.isNullOrBlank()
                    val keepsExistingKey = !request.clearCustomApiKey && !current?.customApiKeyEncrypted.isNullOrBlank()
                    if (!hasNewKey && !keepsExistingKey) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<String>("INVALID_CUSTOM_API_KEY", "自定义模型需要填写 API Key")
                        )
                    }
                }

                val customApiKey = request.customApiKey
                val encryptedKey = when {
                    request.clearCustomApiKey -> null
                    !customApiKey.isNullOrBlank() -> AgentModelConfigResolver.encryptApiKey(customApiKey, quantConfig)
                    else -> current?.customApiKeyEncrypted
                }
                val keepExistingApiKey = customApiKey.isNullOrBlank() && !request.clearCustomApiKey

                val saved = repository.saveModelConfig(
                    userId = userId,
                    workDir = current?.workDir
                        ?: quantConfig.agent.workDir
                        ?: "${System.getProperty("user.home")}/.quant_agents/${userId.toString().take(8)}",
                    isolated = current?.isolated ?: quantConfig.agent.isolated,
                    selectionMode = request.selectedMode.name,
                    presetKey = request.selectedPresetKey,
                    customDisplayName = request.customDisplayName?.trim()?.takeIf { it.isNotEmpty() },
                    customModelId = request.customModelId?.trim()?.takeIf { it.isNotEmpty() },
                    customBaseUrl = request.customBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
                    customApiKeyEncrypted = encryptedKey,
                    keepExistingApiKey = keepExistingApiKey
                )
                val effective = AgentModelConfigResolver.resolve(quantConfig, saved)
                val customKey = AgentModelConfigResolver.decryptApiKey(saved.customApiKeyEncrypted.orEmpty(), quantConfig)
                if (request.resetAgentRuntime) {
                    org.shiroumi.server.websocket.service.AgentWebSocketService.resetUserRuntime(userId)
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        UserAgentConfigDto(
                            presets = AgentModelConfigResolver.presets(quantConfig.agent),
                            defaultPresetKey = AgentModelConfigResolver.defaultPresetKey(quantConfig.agent),
                            selectedMode = effective.selectionMode,
                            selectedPresetKey = saved.modelPresetKey ?: effective.presetKey,
                            customDisplayName = saved.customModelDisplayName,
                            customModelId = saved.customModelId,
                            customBaseUrl = saved.customBaseUrl,
                            hasCustomApiKey = !customKey.isNullOrBlank(),
                            maskedCustomApiKey = AgentModelConfigResolver.maskApiKey(customKey),
                            runtimeModelLabel = effective.displayName,
                            pendingRestart = if (request.resetAgentRuntime) {
                                false
                            } else {
                                org.shiroumi.server.websocket.service.AgentWebSocketService.hasRuntimeConfigDrift(
                                    userId = userId,
                                    modelId = effective.modelId,
                                    baseUrl = effective.baseUrl,
                                    apiKey = effective.apiKey,
                                    provider = effective.provider
                                )
                            }
                        ),
                        message = "Agent 配置已保存"
                    )
                )
            }
        }
    }
}

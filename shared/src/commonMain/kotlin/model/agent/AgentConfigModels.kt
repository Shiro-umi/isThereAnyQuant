package model.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentModelPresetDto(
    val key: String,
    val displayName: String,
    val modelId: String,
    val baseUrl: String? = null,
    val provider: String = "anthropic"
)

@Serializable
enum class AgentModelSelectionMode {
    PRESET,
    CUSTOM
}

@Serializable
data class UserAgentConfigDto(
    val presets: List<AgentModelPresetDto>,
    val defaultPresetKey: String?,
    val selectedMode: AgentModelSelectionMode,
    val selectedPresetKey: String?,
    val customDisplayName: String?,
    val customModelId: String?,
    val customBaseUrl: String?,
    val hasCustomApiKey: Boolean,
    val maskedCustomApiKey: String?,
    val runtimeModelLabel: String?,
    val pendingRestart: Boolean
)

@Serializable
data class UpdateUserAgentConfigRequest(
    val selectedMode: AgentModelSelectionMode,
    val selectedPresetKey: String? = null,
    val customDisplayName: String? = null,
    val customModelId: String? = null,
    val customBaseUrl: String? = null,
    val customApiKey: String? = null,
    val clearCustomApiKey: Boolean = false,
    val resetAgentRuntime: Boolean = false
)

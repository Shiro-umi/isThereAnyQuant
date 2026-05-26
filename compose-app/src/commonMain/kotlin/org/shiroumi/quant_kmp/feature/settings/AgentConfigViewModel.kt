package org.shiroumi.quant_kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import model.agent.AgentModelPresetDto
import model.agent.AgentModelSelectionMode
import model.agent.UpdateUserAgentConfigRequest

data class AgentConfigUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val presets: List<AgentModelPresetDto> = emptyList(),
    val selectedMode: AgentModelSelectionMode = AgentModelSelectionMode.PRESET,
    val selectedPresetKey: String? = null,
    val customDisplayName: String = "",
    val customModelId: String = "",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val hasCustomApiKey: Boolean = false,
    val maskedCustomApiKey: String? = null,
    val runtimeModelLabel: String? = null,
    val pendingRestart: Boolean = false,
    val showSwitchConfirmDialog: Boolean = false,
    val pendingSwitchLabel: String? = null,
    val committedMode: AgentModelSelectionMode = AgentModelSelectionMode.PRESET,
    val committedPresetKey: String? = null
) {
    val isCustomSubmittable: Boolean
        get() = customModelId.isNotBlank() &&
            customBaseUrl.isNotBlank() &&
            (customApiKey.isNotBlank() || hasCustomApiKey)
}

class AgentConfigViewModel(
    private val repository: AgentConfigRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AgentConfigUiState())
    val state: StateFlow<AgentConfigUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.load()
                .onSuccess { config ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            presets = config.presets.ifEmpty { it.presets },
                            selectedMode = config.selectedMode,
                            selectedPresetKey = config.selectedPresetKey ?: config.defaultPresetKey,
                            committedMode = config.selectedMode,
                            committedPresetKey = config.selectedPresetKey ?: config.defaultPresetKey,
                            customDisplayName = config.customDisplayName.orEmpty(),
                            customModelId = config.customModelId.orEmpty(),
                            customBaseUrl = config.customBaseUrl.orEmpty(),
                            customApiKey = "",
                            hasCustomApiKey = config.hasCustomApiKey,
                            maskedCustomApiKey = config.maskedCustomApiKey,
                            runtimeModelLabel = config.runtimeModelLabel,
                            pendingRestart = config.pendingRestart,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Agent 配置加载失败")
                    }
                }
        }
    }

    fun selectPreset(key: String) {
        val snapshot = _state.value
        if (snapshot.committedMode == AgentModelSelectionMode.PRESET && snapshot.committedPresetKey == key) {
            _state.update { it.copy(infoMessage = null, errorMessage = null) }
            return
        }
        val label = snapshot.presets.firstOrNull { it.key == key }?.displayName ?: "预设模型"
        _state.update {
            it.copy(
                selectedMode = AgentModelSelectionMode.PRESET,
                selectedPresetKey = key,
                showSwitchConfirmDialog = true,
                pendingSwitchLabel = label,
                infoMessage = null,
                errorMessage = null
            )
        }
    }

    fun useCustomModel() {
        _state.update {
            it.copy(
                selectedMode = AgentModelSelectionMode.CUSTOM,
                infoMessage = null,
                errorMessage = null
            )
        }
    }

    fun submitCustomModel() {
        val snapshot = _state.value
        if (snapshot.selectedMode != AgentModelSelectionMode.CUSTOM) return
        if (!snapshot.isCustomSubmittable) return
        requestCustomSwitch()
    }

    fun updateCustomDisplayName(value: String) {
        _state.update { it.copy(customDisplayName = value, infoMessage = null) }
    }

    fun updateCustomModelId(value: String) {
        _state.update { it.copy(customModelId = value, infoMessage = null) }
    }

    fun updateCustomBaseUrl(value: String) {
        _state.update { it.copy(customBaseUrl = value, infoMessage = null) }
    }

    fun updateCustomApiKey(value: String) {
        _state.update { it.copy(customApiKey = value, infoMessage = null) }
    }

    fun cancelPendingSwitch() {
        _state.update {
            it.copy(
                selectedMode = it.committedMode,
                selectedPresetKey = it.committedPresetKey,
                showSwitchConfirmDialog = false,
                pendingSwitchLabel = null,
                infoMessage = null
            )
        }
    }

    fun confirmPendingSwitch(onApplied: () -> Unit) {
        val snapshot = _state.value
        _state.update { it.copy(isSaving = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            val request = UpdateUserAgentConfigRequest(
                selectedMode = snapshot.selectedMode,
                selectedPresetKey = snapshot.selectedPresetKey,
                customDisplayName = snapshot.customDisplayName,
                customModelId = snapshot.customModelId,
                customBaseUrl = snapshot.customBaseUrl,
                customApiKey = snapshot.customApiKey.takeIf { it.isNotBlank() },
                clearCustomApiKey = false,
                resetAgentRuntime = true
            )
            repository.save(request)
                .onSuccess { config ->
                    onApplied()
                    _state.update {
                        it.copy(
                            isSaving = false,
                            presets = config.presets.ifEmpty { it.presets },
                            selectedMode = config.selectedMode,
                            selectedPresetKey = config.selectedPresetKey ?: config.defaultPresetKey,
                            committedMode = config.selectedMode,
                            committedPresetKey = config.selectedPresetKey ?: config.defaultPresetKey,
                            customDisplayName = config.customDisplayName.orEmpty(),
                            customModelId = config.customModelId.orEmpty(),
                            customBaseUrl = config.customBaseUrl.orEmpty(),
                            customApiKey = "",
                            hasCustomApiKey = config.hasCustomApiKey,
                            maskedCustomApiKey = config.maskedCustomApiKey,
                            runtimeModelLabel = config.runtimeModelLabel,
                            pendingRestart = config.pendingRestart,
                            showSwitchConfirmDialog = false,
                            pendingSwitchLabel = null,
                            infoMessage = "已切换模型；Agent 对话已重新连接"
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            selectedMode = it.committedMode,
                            selectedPresetKey = it.committedPresetKey,
                            showSwitchConfirmDialog = false,
                            pendingSwitchLabel = null,
                            errorMessage = error.message ?: "Agent 配置保存失败"
                        )
                    }
                }
        }
    }

    private fun requestCustomSwitch() {
        _state.update {
            it.copy(
                selectedMode = AgentModelSelectionMode.CUSTOM,
                showSwitchConfirmDialog = true,
                pendingSwitchLabel = it.customDisplayName.ifBlank { "自定义模型" },
                infoMessage = null,
                errorMessage = null
            )
        }
    }
}

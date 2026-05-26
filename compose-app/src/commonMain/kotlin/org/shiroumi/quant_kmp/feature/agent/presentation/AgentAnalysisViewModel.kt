package org.shiroumi.quant_kmp.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import model.ws.WsAction
import model.ws.WsTopic
import org.shiroumi.quant_kmp.feature.agent.contract.AgentAnalysisContract
import org.shiroumi.quant_kmp.feature.agent.data.repository.AgentAnalysisRepository
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.ui.theme.ThemeBrightnessMode
import org.shiroumi.quant_kmp.ui.theme.ThemeManager

class AgentAnalysisViewModel(
    private val repository: AgentAnalysisRepository,
    private val webSocketClient: GlobalWebSocketClient = GlobalWebSocketClient
) : ViewModel(), org.shiroumi.quant_kmp.ui.core.mvi.MviViewModel<
    AgentAnalysisContract.State,
    AgentAnalysisContract.Action,
    AgentAnalysisContract.Effect
> {

    private val _state = MutableStateFlow(AgentAnalysisContract.State())
    override val state: StateFlow<AgentAnalysisContract.State> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<AgentAnalysisContract.Effect>()
    override val effect: SharedFlow<AgentAnalysisContract.Effect> = _effect.asSharedFlow()

    init {
        dispatch(AgentAnalysisContract.Action.LoadResults)
        observeAgentCompletions()
    }

    @OptIn(FlowPreview::class)
    private fun observeAgentCompletions() {
        viewModelScope.launch {
            webSocketClient.eventsFlow
                .filter { it.topic == WsTopic.AGENT_STREAM && it.action == WsAction.COMPLETE }
                .debounce(AGENT_COMPLETION_DEBOUNCE_MS)
                .collect { dispatch(AgentAnalysisContract.Action.LoadResults) }
        }
    }

    private companion object {
        const val AGENT_COMPLETION_DEBOUNCE_MS = 500L
    }

    override fun dispatch(action: AgentAnalysisContract.Action) {
        when (action) {
            is AgentAnalysisContract.Action.LoadResults -> loadResults()
            is AgentAnalysisContract.Action.ResultsLoaded -> _state.update {
                it.copy(results = action.results, isLoading = false, errorMessage = null)
            }
            is AgentAnalysisContract.Action.LoadFailed -> _state.update {
                it.copy(isLoading = false, errorMessage = action.message)
            }
            is AgentAnalysisContract.Action.SelectResult -> {
                _state.update { it.copy(selectedResult = action.result, selectedShareStats = null) }
                loadShareStats(action.result.id)
            }
            is AgentAnalysisContract.Action.DeleteResult -> deleteResult(action.id)
            is AgentAnalysisContract.Action.DeleteSuccess -> _state.update {
                it.copy(
                    results = it.results.filter { r -> r.id != action.id },
                    selectedResult = if (it.selectedResult?.id == action.id) null else it.selectedResult,
                    selectedShareStats = if (it.selectedResult?.id == action.id) null else it.selectedShareStats,
                    isLoading = false
                )
            }
            is AgentAnalysisContract.Action.DeleteFailed -> _state.update {
                it.copy(isLoading = false, errorMessage = action.message)
            }
            is AgentAnalysisContract.Action.CreateShare -> createShare(action.id)
            is AgentAnalysisContract.Action.ShareStatsLoaded -> _state.update {
                it.copy(selectedShareStats = action.stats)
            }
            is AgentAnalysisContract.Action.ClearError -> _state.update {
                it.copy(errorMessage = null)
            }
        }
    }

    private fun loadShareStats(id: String) {
        viewModelScope.launch {
            repository.getShareStats(id).onSuccess { stats ->
                if (_state.value.selectedResult?.id == id) {
                    dispatch(AgentAnalysisContract.Action.ShareStatsLoaded(stats))
                }
            }
        }
    }

    private fun createShare(id: String) {
        _state.update { it.copy(isSharing = true) }
        val pref = ThemeManager.preference.value
        val themeName = pref?.theme?.name
        val isDark = when (pref?.brightness) {
            ThemeBrightnessMode.Dark -> true
            ThemeBrightnessMode.Light -> false
            else -> true
        }
        viewModelScope.launch {
            repository.createShareLink(id, themeName = themeName, isDark = isDark)
                .onSuccess { resp ->
                    _state.update { it.copy(isSharing = false) }
                    if (_state.value.selectedResult?.id == id) {
                        loadShareStats(id)
                    }
                    _effect.emit(AgentAnalysisContract.Effect.CopyShareUrl(resp.shareUrl))
                }
                .onFailure { error ->
                    _state.update { it.copy(isSharing = false) }
                    _effect.emit(AgentAnalysisContract.Effect.ShowToast("分享失败: ${error.message}"))
                }
        }
    }

    private fun loadResults() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = repository.getAnalysisResults(limit = 100)
            result.onSuccess { list ->
                dispatch(AgentAnalysisContract.Action.ResultsLoaded(list))
            }.onFailure { error ->
                dispatch(AgentAnalysisContract.Action.LoadFailed(error.message ?: "加载失败"))
            }
        }
    }

    private fun deleteResult(id: String) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = repository.deleteAnalysisResult(id)
            result.onSuccess {
                dispatch(AgentAnalysisContract.Action.DeleteSuccess(id))
                _effect.emit(AgentAnalysisContract.Effect.ShowToast("删除成功"))
            }.onFailure { error ->
                dispatch(AgentAnalysisContract.Action.DeleteFailed(error.message ?: "删除失败"))
                _effect.emit(AgentAnalysisContract.Effect.ShowToast("删除失败: ${error.message}"))
            }
        }
    }
}

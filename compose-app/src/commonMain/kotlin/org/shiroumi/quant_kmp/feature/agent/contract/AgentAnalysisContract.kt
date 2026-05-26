package org.shiroumi.quant_kmp.feature.agent.contract

import model.agent.AgentAnalysisResultDto
import model.agent.ShareStatsDto
import org.shiroumi.quant_kmp.ui.core.mvi.UiAction
import org.shiroumi.quant_kmp.ui.core.mvi.UiEffect
import org.shiroumi.quant_kmp.ui.core.mvi.UiState

object AgentAnalysisContract {

    data class State(
        val results: List<AgentAnalysisResultDto> = emptyList(),
        val selectedResult: AgentAnalysisResultDto? = null,
        /** 当前选中记录的分享统计；selectedResult 切换时会重置为 null 再异步加载 */
        val selectedShareStats: ShareStatsDto? = null,
        val isSharing: Boolean = false,
        override val isLoading: Boolean = false,
        override val errorMessage: String? = null
    ) : UiState

    sealed interface Action : UiAction {
        data object LoadResults : Action
        data class ResultsLoaded(val results: List<AgentAnalysisResultDto>) : Action
        data class LoadFailed(val message: String) : Action
        data class SelectResult(val result: AgentAnalysisResultDto) : Action
        data class DeleteResult(val id: String) : Action
        data class DeleteSuccess(val id: String) : Action
        data class DeleteFailed(val message: String) : Action
        data class CreateShare(val id: String) : Action
        data class ShareStatsLoaded(val stats: ShareStatsDto) : Action
        data object ClearError : Action
    }

    sealed interface Effect : UiEffect {
        data class ShowToast(val message: String) : Effect
        /** 分享生成成功，要求 UI 把链接写入剪贴板 */
        data class CopyShareUrl(val url: String) : Effect
    }
}

package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AgentSidebar ViewModel
 *
 * 管理全局 Agent 侧边栏的状态和交互逻辑，生命周期跟随整个应用。
 * 通过 [AgentSidebarState] 描述状态，提供类型安全的状态更新方法。
 *
 * 此类在 [Navigation.kt][org.shiroumi.quant_kmp.ui.navigation.Navigation] 中创建，
 * 并通过 [LocalAgentSidebarViewModel] 提供给子 Compose 树。
 */
class AgentSidebarViewModel(
    initialState: AgentSidebarState = AgentSidebarState.forDesktop()
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)

    /**
     * 当前侧边栏状态流
     *
     * 订阅此 StateFlow 以响应式地获取状态变化。
     * 在 Compose 中使用 `collectAsState()` 订阅。
     */
    val state: StateFlow<AgentSidebarState> = _state.asStateFlow()

    /**
     * 切换面板展开/折叠状态
     *
     * 如果当前展开则折叠，反之亦然。
     */
    fun toggleExpand() {
        _state.update { it.copy(isExpanded = !it.isExpanded) }
    }

    /**
     * 设置面板展开状态
     *
     * @param expanded true=展开，false=折叠
     */
    fun setExpanded(expanded: Boolean) {
        _state.update { it.copy(isExpanded = expanded) }
    }

    /**
     * 选中指定会话
     *
     * @param sessionId 会话唯一标识符
     */
    fun selectSession(sessionId: String) {
        _state.update { it.copy(selectedSessionId = sessionId) }
    }

    /**
     * 清除当前选中的会话
     *
     * 将 [AgentSidebarState.selectedSessionId] 置为 null。
     */
    fun clearSession() {
        _state.update { it.copy(selectedSessionId = null) }
    }

    /**
     * 设置面板可见性
     *
     * @param visible true=可见，false=隐藏
     */
    fun setVisible(visible: Boolean) {
        _state.update { it.copy(isVisible = visible) }
    }

    /**
     * 展开面板并选中指定会话
     *
     * 这是一个组合操作，常用于从外部触发侧边栏展开并定位到特定会话。
     *
     * @param sessionId 要选中并展开的会话ID
     */
    fun expandAndSelectSession(sessionId: String) {
        _state.update {
            it.copy(
                isExpanded = true,
                selectedSessionId = sessionId
            )
        }
    }
    /**
     * 更新侧边栏自定义宽度
     *
     * @param widthPx 宽度像素值
     */
    fun updateWidth(widthPx: Int) {
        _state.update { it.copy(customWidth = widthPx) }
    }
}

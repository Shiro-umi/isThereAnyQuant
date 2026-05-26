package org.shiroumi.quant_kmp.ui.agent.sidebar

/**
 * AgentSidebar 状态数据类
 *
 * 管理全局 Agent 侧边栏的 UI 状态，包括展开/折叠、可见性、选中会话等。
 * 此状态类独立于业务逻辑，仅描述侧边栏自身的状态。
 *
 * @property isExpanded 面板是否展开（true=展开，false=折叠）
 * @property selectedSessionId 当前选中的会话ID，null表示未选中
 * @property isVisible 面板是否可见（根据屏幕尺寸动态调整，Compact模式下隐藏）
 */
data class AgentSidebarState(
    val isExpanded: Boolean = true,
    val selectedSessionId: String? = null,
    val isVisible: Boolean = true,
    val customWidth: Int? = null
) {
    companion object {
        /**
         * 桌面/平板端默认状态：侧边栏默认展开
         */
        fun forDesktop() = AgentSidebarState(isExpanded = true)

        /**
         * 移动端默认状态：侧边栏默认折叠，避免遮挡主内容
         */
        fun forMobile() = AgentSidebarState(isExpanded = false)
    }
}

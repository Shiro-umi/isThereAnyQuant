package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * AgentSidebar 的 CompositionLocal 定义
 *
 * 用于在 Compose 树中向下传递状态和 ViewModel，避免通过参数层层传递。
 * 这些 CompositionLocal 在 [Navigation.kt][org.shiroumi.quant_kmp.ui.navigation.Navigation] 中初始化并提供。
 */

/**
 * 提供当前 [AgentSidebarState] 的 CompositionLocal
 *
 * 默认值为 null，必须在 Navigation 层通过 [androidx.compose.runtime.CompositionLocalProvider] 初始化后使用。
 * 使用方应通过 `LocalAgentSidebarState.current` 获取当前状态，并做好 null 安全检查。
 *
 * 示例：
 * ```kotlin
 * val sidebarState = LocalAgentSidebarState.current
 * sidebarState?.let { state ->
 *     // 使用 state.isExpanded 等属性
 * }
 * ```
 */
val LocalAgentSidebarState = staticCompositionLocalOf<AgentSidebarState?> {
    null
}

/**
 * 提供 [AgentSidebarViewModel] 的 CompositionLocal
 *
 * 默认值为 null，必须在 Navigation 层通过 [androidx.compose.runtime.CompositionLocalProvider] 初始化后使用。
 * 使用方应通过 `LocalAgentSidebarViewModel.current` 获取 ViewModel 实例，并调用其方法来修改状态。
 *
 * 示例：
 * ```kotlin
 * val viewModel = LocalAgentSidebarViewModel.current
 * viewModel?.toggleExpand()
 * ```
 */
val LocalAgentSidebarViewModel = staticCompositionLocalOf<AgentSidebarViewModel?> {
    null
}

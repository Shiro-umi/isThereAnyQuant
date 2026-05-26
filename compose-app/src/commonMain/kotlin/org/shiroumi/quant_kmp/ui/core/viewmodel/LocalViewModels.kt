package org.shiroumi.quant_kmp.ui.core.viewmodel

import androidx.compose.runtime.compositionLocalOf
import org.shiroumi.quant_kmp.feature.auth.AuthViewModel
import org.shiroumi.quant_kmp.ui.agent.viewmodel.AgentViewModel

/**
 * Auth 功能域 ViewModel 的 CompositionLocal 持有者。
 *
 * 必须在 [org.shiroumi.quant_kmp.ui.auth.AuthGate] 作用域内 provide，
 * 在其组合树内的任意子 Composable 均可通过 [LocalAuthViewModel].current 获取。
 */
val LocalAuthViewModel = compositionLocalOf<AuthViewModel> {
    error("LocalAuthViewModel 未被 provide，请在 AuthGate 作用域内调用 CompositionLocalProvider(LocalAuthViewModel provides viewModel) { ... }")
}

/**
 * Agent 功能域 ViewModel 的 CompositionLocal 持有者。
 *
 * 必须在全局导航壳作用域内 provide，供 Sidebar/FloatingCard 等 Agent 入口使用。
 */
val LocalAgentViewModel = compositionLocalOf<AgentViewModel> {
    error("LocalAgentViewModel 未被 provide，请在导航壳作用域内调用 CompositionLocalProvider(LocalAgentViewModel provides viewModel) { ... }")
}

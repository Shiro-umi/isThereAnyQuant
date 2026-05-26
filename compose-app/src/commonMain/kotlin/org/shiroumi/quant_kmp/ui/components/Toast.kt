package org.shiroumi.quant_kmp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Toast 状态管理器 - 平台无关接口
 * 各平台需要提供具体实现
 */
expect class ToastHostState {
    /**
     * 显示一个 Toast
     * @param message 要显示的消息
     * @param durationMillis 显示的持续时间（毫秒）
     */
    suspend fun showToast(message: String, durationMillis: Long = 3000L)
}

/**
 * Toast 宿主 Composable - 平台无关接口
 * 各平台需要提供具体实现
 * @param hostState Toast 状态管理器
 * @param modifier Modifier
 * @param alignment Toast 在屏幕上的对齐方式，默认为底部居中
 */
@Composable
expect fun ToastHost(
    hostState: ToastHostState,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter
)

/**
 * 创建和记住 ToastHostState 实例的辅助函数 - 平台无关接口
 * 各平台需要提供具体实现
 */
@Composable
expect fun rememberToastHostState(): ToastHostState

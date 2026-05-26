package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // Desktop (JVM) 平台：目前为空实现
    // Compose Desktop 窗口最小化/恢复事件可以通过 WindowState 监听
}

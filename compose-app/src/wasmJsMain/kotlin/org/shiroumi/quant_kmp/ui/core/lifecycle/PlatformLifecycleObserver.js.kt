package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // JS 平台：浏览器页面可见性变化由 Visibility API 处理
    // 这里暂时为空实现，需要时可以通过 document.visibilityState 监听
}

package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable

/**
 * 平台特定的生命周期观察者
 * 在 Android 上监听 Activity 生命周期
 * 在其他平台上目前为空实现
 */
@Composable
expect fun PlatformLifecycleEffect(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {}
)

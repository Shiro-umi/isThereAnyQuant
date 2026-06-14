package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable

/**
 * 桌面（JVM）平台生命周期。
 *
 * 当前 compose-app 未声明 jvm target，本源码集不参与编译，保持空实现。
 * 若将来启用桌面端，可用窗口焦点事件（AWTEventListener + WINDOW_GAINED/LOST_FOCUS）
 * 映射 onResume/onPause，对齐 wasmJs 的 Visibility 约定。
 */
@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // no-op：jvm target 未启用
}

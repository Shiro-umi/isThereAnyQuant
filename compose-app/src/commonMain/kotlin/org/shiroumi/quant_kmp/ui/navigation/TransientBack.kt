package org.shiroumi.quant_kmp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState

/**
 * 通过 CompositionLocal 暴露当前 [NavigationState]，供 supporting pane / 弹层等
 * 将自己的"打开"状态注册到统一返回链路。
 */
val LocalNavigationState = compositionLocalOf<NavigationState?> { null }

/**
 * 将一个临时弹层（pane / sheet 等）的打开状态注册到 [NavigationState]。
 *
 * - [isOpen] 反映当前是否处于"已打开"状态；
 * - [onClose] 在用户触发返回时被调用，用来关闭该弹层；
 * - 注册期间，[NavigationState.browserBackDepth] 会把该弹层算入深度，
 *   [Navigator.goBack] 会优先调用 [onClose] 再回退栈。
 *
 * 若当前没有 [LocalNavigationState]，本调用为 no-op，保证组件可独立预览/测试。
 */
@Composable
fun RegisterTransientBack(isOpen: Boolean, onClose: () -> Unit) {
    val navigationState = LocalNavigationState.current ?: return
    val openState = rememberUpdatedState(isOpen)
    val closeState = rememberUpdatedState(onClose)

    DisposableEffect(navigationState) {
        val consumer = TransientBackConsumer(
            isOpen = { openState.value },
            onConsume = { closeState.value.invoke() }
        )
        navigationState.registerTransientBackConsumer(consumer)
        onDispose { navigationState.unregisterTransientBackConsumer(consumer) }
    }
}

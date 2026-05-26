package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Android 平台实现：获取安全区域内边距
 *
 * 使用 WindowInsets API 获取系统提供的安全区域值
 */
@Composable
actual fun rememberSafeAreaInsets(): SafeAreaInsets {
    val insetsState = rememberSafeAreaInsetsState()
    return insetsState.value
}

/**
 * 记住安全区域内边距状态
 */
@Composable
private fun rememberSafeAreaInsetsState(): State<SafeAreaInsets> {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // 使用 Android 的 WindowInsets API 获取安全区域
    val statusBarsInsets = WindowInsets.statusBars.asPaddingValues()
    val navigationBarsInsets = WindowInsets.navigationBars.asPaddingValues()
    val displayCutoutInsets = WindowInsets.displayCutout.asPaddingValues()

    return remember(statusBarsInsets, navigationBarsInsets, displayCutoutInsets, density) {
        val top = with(density) {
            (statusBarsInsets.calculateTopPadding() + displayCutoutInsets.calculateTopPadding()).toPx()
        }
        val bottom = with(density) {
            navigationBarsInsets.calculateBottomPadding().toPx()
        }
        val left = with(density) {
            (displayCutoutInsets.calculateLeftPadding(layoutDirection)).toPx()
        }
        val right = with(density) {
            (displayCutoutInsets.calculateRightPadding(layoutDirection)).toPx()
        }

        mutableStateOf(
            SafeAreaInsets(
                top = top,
                right = right,
                bottom = bottom,
                left = left
            )
        )
    }
}

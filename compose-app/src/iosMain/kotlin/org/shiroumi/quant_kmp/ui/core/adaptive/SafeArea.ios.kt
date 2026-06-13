package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * iOS 平台实现：获取安全区域内边距。
 *
 * Compose Multiplatform 在 iOS 上把刘海/灵动岛/Home Indicator/圆角避让统一汇聚到
 * [WindowInsets.safeDrawing]，这里换算成 px 填入 [SafeAreaInsets]，
 * 与 Web 的 CSS env() / Android 的 WindowInsets 语义对齐。
 */
@Composable
actual fun rememberSafeAreaInsets(): SafeAreaInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()

    return remember(safeDrawing, density, layoutDirection) {
        with(density) {
            SafeAreaInsets(
                top = safeDrawing.calculateTopPadding().toPx(),
                right = safeDrawing.calculateRightPadding(layoutDirection).toPx(),
                bottom = safeDrawing.calculateBottomPadding().toPx(),
                left = safeDrawing.calculateLeftPadding(layoutDirection).toPx(),
            )
        }
    }
}

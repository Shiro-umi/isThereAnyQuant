package org.shiroumi.quant_kmp.ui.core.adaptive.m3

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * Material 3 Adaptive 兼容版布局配置。
 *
 * 基于官方 [currentWindowAdaptiveInfo] 的 5 档断点：
 * - Compact:  < 600dp
 * - Medium:   600dp – 839dp
 * - Expanded: 840dp – 1199dp
 * - Large:    1200dp – 1599dp
 * - XLarge:   ≥ 1600dp
 *
 * 对外接口与旧 [org.shiroumi.quant_kmp.ui.core.adaptive.AdaptiveLayoutConfig] 语义兼容，
 * 使老页面无须立刻修改。
 */
@Stable
data class AdaptiveLayoutConfig(
    val widthClass: WindowWidthType,
    val heightClass: WindowHeightType,
    val windowWidth: Dp,
    val windowHeight: Dp,
) {
    val isCompact: Boolean get() = widthClass == WindowWidthType.Compact
    val isMedium: Boolean get() = widthClass == WindowWidthType.Medium
    val isExpanded: Boolean get() = widthClass == WindowWidthType.Expanded
    val isLarge: Boolean get() = widthClass == WindowWidthType.Large
    val isXLarge: Boolean get() = widthClass == WindowWidthType.XLarge

    /** 兼容旧语义：Compact 单栏；其余双栏 */
    val useTwoPane: Boolean get() = !isCompact

    /** 兼容旧语义：底部导航仅 Compact */
    val useBottomNavigation: Boolean get() = isCompact
    val useNavigationRail: Boolean get() = !isCompact

    /** 内容区域最大宽度 */
    val contentMaxWidth: Dp get() = when (widthClass) {
        WindowWidthType.Compact -> 600.dp
        WindowWidthType.Medium -> 840.dp
        WindowWidthType.Expanded -> 1200.dp
        WindowWidthType.Large -> 1400.dp
        WindowWidthType.XLarge -> 1600.dp
    }

    /** 列表/详情分割比例 */
    val listDetailRatio: Pair<Float, Float> get() = when (widthClass) {
        WindowWidthType.Compact -> 1f to 0f
        WindowWidthType.Medium -> 0.4f to 0.6f
        WindowWidthType.Expanded -> 0.35f to 0.65f
        WindowWidthType.Large -> 0.3f to 0.7f
        WindowWidthType.XLarge -> 0.25f to 0.75f
    }
}

enum class WindowWidthType {
    Compact,    // < 600dp
    Medium,     // 600dp – 839dp
    Expanded,   // 840dp – 1199dp
    Large,      // 1200dp – 1599dp
    XLarge      // ≥ 1600dp
}

enum class WindowHeightType {
    Compact,    // < 480dp
    Medium,     // 480dp – 899dp
    Expanded    // ≥ 900dp
}

/**
 * 获取当前自适应布局配置（新版 V2）。
 *
 * 使用官方 [currentWindowAdaptiveInfo] 计算断点，支持 Large / XLarge 宽度类别。
 */
@Composable
fun rememberAdaptiveLayoutConfig(): AdaptiveLayoutConfig {
    val info = currentWindowAdaptiveInfo()
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val containerSize = windowInfo.containerSize

    return remember(info, density, containerSize) {
        val size = with(density) { containerSize.toSize().toDpSize() }
        val widthDp = size.width
        val heightDp = size.height

        val widthClass = when {
            info.windowSizeClass.isWidthAtLeastBreakpoint(1600) -> WindowWidthType.XLarge
            info.windowSizeClass.isWidthAtLeastBreakpoint(1200) -> WindowWidthType.Large
            info.windowSizeClass.isWidthAtLeastBreakpoint(840) -> WindowWidthType.Expanded
            info.windowSizeClass.isWidthAtLeastBreakpoint(600) -> WindowWidthType.Medium
            else -> WindowWidthType.Compact
        }

        val heightClass = when {
            info.windowSizeClass.isHeightAtLeastBreakpoint(900) -> WindowHeightType.Expanded
            info.windowSizeClass.isHeightAtLeastBreakpoint(480) -> WindowHeightType.Medium
            else -> WindowHeightType.Compact
        }

        AdaptiveLayoutConfig(
            widthClass = widthClass,
            heightClass = heightClass,
            windowWidth = widthDp,
            windowHeight = heightDp
        )
    }
}

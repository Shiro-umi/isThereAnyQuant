package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 应用全局主题入口。
 *
 * 所有页面和组件必须在此 Composable 的作用域内运行，以获得：
 * - 由 [ThemeManager] 驱动的可切换 Material 3 色彩系统（8 种主题色 × 亮/暗/跟随系统）
 * - 业务语义色（涨跌 / 警告 / 成功）通过 [LocalQuantColors] 暴露
 * - 统一的 Noto Sans SC 排版（[AppTypography]）
 *
 * 当前生效的 [AppThemeState] 通过 [LocalAppThemeState] 暴露给下游，供设置页等读取。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val preference = rememberThemePreference() ?: return
    val systemDark = isSystemInDarkTheme()
    val useDarkColors = when (preference.brightness) {
        ThemeBrightnessMode.System -> systemDark
        ThemeBrightnessMode.Light -> false
        ThemeBrightnessMode.Dark -> true
    }
    val colorScheme = remember(preference.theme, useDarkColors) {
        resolveColorScheme(preference.theme, useDarkColors)
    }
    LaunchedEffect(colorScheme) {
        syncLoadingChrome(colorScheme)
    }
    val semanticColors = remember(preference.theme, useDarkColors) {
        resolveSemanticColors(preference.theme, useDarkColors)
    }
    val themeState = remember(preference, useDarkColors) {
        AppThemeState(
            preference = preference,
            useDarkColors = useDarkColors,
        )
    }

    CompositionLocalProvider(
        LocalAppThemeState provides themeState,
        LocalQuantColors provides semanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography(),
        ) {
            // 根背景兜底：全屏铺底 colorScheme.background，三端统一。
            // iOS（ignoresSafeArea 让 Compose 铺满全屏）依赖此层填充刘海/Home 区，
            // 避免安全区外露出系统白底；导航转场期间底层恒为主题色，杜绝透明闪现。
            // 用 Box+background 而非 Surface：Surface 含 SuspendingPointerInputModifierNode 会
            // 参与 pointer 事件链，在 Web(Wasm) 触摸场景下干扰 backing input 的焦点稳定，导致
            // 移动端软键盘"弹出即收"；纯绘制铺底的 Box 不挂 pointer 节点，规避该交互。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                content()
            }
        }
    }
}

val emptyMutableInteractionSource: MutableInteractionSource
    get() = object : MutableInteractionSource {
        override val interactions: Flow<Interaction>
            get() = flow { }

        override suspend fun emit(interaction: Interaction) {

        }

        override fun tryEmit(interaction: Interaction): Boolean {
            return false
        }

    }

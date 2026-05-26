package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.shiroumi.compose_app.generated.resources.NotoColorEmoji
import org.shiroumi.compose_app.generated.resources.NotoSansSC_Bold
import org.shiroumi.compose_app.generated.resources.NotoSansSC_Light
import org.shiroumi.compose_app.generated.resources.NotoSansSC_Medium
import org.shiroumi.compose_app.generated.resources.NotoSansSC_Regular
import org.shiroumi.compose_app.generated.resources.Res

/**
 * 应用字体家族 - 缓存优化版本
 *
 * 字体族以 Noto Sans SC 为主，并保留 Noto Color Emoji 作为既有 emoji 支持路径。
 * Noto Sans SC 必须覆盖 Material 3 常用的 W300/W400/W500/W700；否则 W500 文本
 * 会被匹配到 Bold，导致设置页、按钮、标签等业务文字整体发重。
 *
 * 优化策略：
 * - 使用 remember 缓存 FontFamily 实例，避免重复加载
 * - 字体文件由 Compose 资源系统缓存
 */
@Composable
fun appFontFamily(): FontFamily {
    val notoLight = Font(
        resource = Res.font.NotoSansSC_Light,
        weight = FontWeight.Light,
        style = FontStyle.Normal
    )
    val notoRegular = Font(
        resource = Res.font.NotoSansSC_Regular,
        weight = FontWeight.Normal,
        style = FontStyle.Normal
    )
    val notoMedium = Font(
        resource = Res.font.NotoSansSC_Medium,
        weight = FontWeight.Medium,
        style = FontStyle.Normal
    )
    val notoBold = Font(
        resource = Res.font.NotoSansSC_Bold,
        weight = FontWeight.Bold,
        style = FontStyle.Normal
    )
    val emojiNormal = Font(
        resource = Res.font.NotoColorEmoji,
        weight = FontWeight.Normal,
        style = FontStyle.Normal
    )
    val emojiBold = Font(
        resource = Res.font.NotoColorEmoji,
        weight = FontWeight.Bold,
        style = FontStyle.Normal
    )

    return remember(notoLight, notoRegular, notoMedium, notoBold, emojiNormal, emojiBold) {
        FontFamily(notoLight, notoRegular, notoMedium, notoBold, emojiNormal, emojiBold)
    }
}

/**
 * 应用排版样式
 * 
 * 所有文本样式使用统一的字体家族
 */
@Composable
fun AppTypography(): Typography {
    // 使用 remember 缓存 Typography
    val fontFamily = appFontFamily()
    
    return remember(fontFamily) {
        val defaultTypography = Typography()
        Typography(
            displayLarge = defaultTypography.displayLarge.copy(fontFamily = fontFamily),
            displayMedium = defaultTypography.displayMedium.copy(fontFamily = fontFamily),
            displaySmall = defaultTypography.displaySmall.copy(fontFamily = fontFamily),

            headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = fontFamily),

            titleLarge = defaultTypography.titleLarge.copy(fontFamily = fontFamily),
            titleMedium = defaultTypography.titleMedium.copy(fontFamily = fontFamily),
            titleSmall = defaultTypography.titleSmall.copy(fontFamily = fontFamily),

            bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = fontFamily),
            bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = fontFamily),
            bodySmall = defaultTypography.bodySmall.copy(fontFamily = fontFamily),

            labelLarge = defaultTypography.labelLarge.copy(fontFamily = fontFamily),
            labelMedium = defaultTypography.labelMedium.copy(fontFamily = fontFamily),
            labelSmall = defaultTypography.labelSmall.copy(fontFamily = fontFamily)
        )
    }
}

/**
 * Markdown 专用排版样式
 */
@Composable
fun MarkdownTypography(): Typography {
    val baseTypography = AppTypography()
    
    return remember(baseTypography) {
        baseTypography.copy(
            bodyMedium = baseTypography.bodyMedium.copy(
                fontSize = 14.sp
            )
        )
    }
}

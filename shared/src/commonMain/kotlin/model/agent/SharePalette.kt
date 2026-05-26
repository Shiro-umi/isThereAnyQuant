package model.agent

import kotlinx.serialization.Serializable

/**
 * 分享页主题色板。
 *
 * 与 Compose 端 Material 3 ColorScheme 1:1 对齐：字段名即 M3 token 名（驼峰），
 * 值为 0xAARRGGBB 形式的 ARGB Long。前端 createShareLink 时上传当前主题，
 * 后端把 token + brightness 持久化到 share_theme/share_brightness 列，
 * HTML 渲染时按主题查 palette → 输出 `--md-sys-color-*` CSS 变量。
 *
 * 之所以不依赖 androidx Color：shared 模块要被后端、Compose 共同消费，
 * 不能引入 androidx.compose.material3。
 */
@Serializable
data class SharePalette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
)

package org.shiroumi.quant_kmp.ui.theme

import kotlinx.serialization.Serializable

/**
 * 用户的亮暗模式偏好。
 *
 * - [System]：跟随系统设置。
 * - [Light] / [Dark]：用户显式选择。
 */
@Serializable
enum class ThemeBrightnessMode {
    System, Light, Dark
}

/** 持久化的用户主题偏好（主题色相 + 亮暗模式）。 */
@Serializable
data class ThemePreference(
    val theme: AppColorTheme = AppColorTheme.WarmBrownRed,
    val brightness: ThemeBrightnessMode = ThemeBrightnessMode.Dark,
)

/**
 * 跨平台的主题偏好持久化存储：
 * - Android：DataStore preferences
 * - Web / Wasm：localStorage
 */
expect object ThemePreferenceStore {
    suspend fun load(): ThemePreference?
    suspend fun save(preference: ThemePreference)
}

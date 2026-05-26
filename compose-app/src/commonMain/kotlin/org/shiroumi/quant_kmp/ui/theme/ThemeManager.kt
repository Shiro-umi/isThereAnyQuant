package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局主题状态管理器。
 *
 * 负责：
 * - 从平台存储恢复用户主题偏好（首次访问时懒加载）
 * - 通过 [preference] StateFlow 暴露当前偏好，供 [AppTheme] 订阅
 * - 在用户切换主题时立即更新内存状态并异步持久化
 */
object ThemeManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _preference = MutableStateFlow<ThemePreference?>(null)
    val preference: StateFlow<ThemePreference?> = _preference.asStateFlow()

    private var restored = false

    /** 首次启动时调用，从持久化存储恢复偏好。多次调用安全。 */
    suspend fun restore() {
        if (restored) return
        restored = true
        _preference.value = ThemePreferenceStore.load() ?: ThemePreference()
    }

    fun setTheme(theme: AppColorTheme) {
        update { it.copy(theme = theme) }
    }

    fun setBrightness(mode: ThemeBrightnessMode) {
        update { it.copy(brightness = mode) }
    }

    private fun update(transform: (ThemePreference) -> ThemePreference) {
        val current = _preference.value ?: ThemePreference()
        val updated = transform(current)
        if (updated == _preference.value) return
        _preference.value = updated
        scope.launch { ThemePreferenceStore.save(updated) }
    }
}

/** 当前生效的主题状态：用于 UI 层观察。 */
data class AppThemeState(
    val preference: ThemePreference,
    val useDarkColors: Boolean,
)

/** 在 [AppTheme] 内部 provide，供 UI 层（如设置页）读取当前主题状态。 */
val LocalAppThemeState = compositionLocalOf<AppThemeState> {
    error("LocalAppThemeState not provided — wrap content in AppTheme")
}

/**
 * 订阅 [ThemeManager.preference]，并在首次组合时触发 [ThemeManager.restore]。
 * 返回 null 表示持久化偏好尚未恢复，调用方不应先用默认主题渲染业务内容。
 */
@Composable
internal fun rememberThemePreference(): ThemePreference? {
    val preference by ThemeManager.preference.collectAsState()
    LaunchedEffect(Unit) { ThemeManager.restore() }
    return preference
}

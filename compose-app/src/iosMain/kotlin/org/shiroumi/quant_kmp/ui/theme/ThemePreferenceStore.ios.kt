package org.shiroumi.quant_kmp.ui.theme

import kotlinx.serialization.encodeToString
import org.shiroumi.quant_kmp.AppJson
import platform.Foundation.NSUserDefaults

/**
 * iOS 主题偏好持久化：NSUserDefaults。
 * 与 Android DataStore / Web localStorage 同样以 JSON 字符串落盘。
 */
actual object ThemePreferenceStore {

    private const val PREFERENCE_KEY = "quant.theme.preference"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual suspend fun load(): ThemePreference? {
        val encoded = defaults.stringForKey(PREFERENCE_KEY) ?: return null
        return runCatching { AppJson.decodeFromString<ThemePreference>(encoded) }.getOrNull()
    }

    actual suspend fun save(preference: ThemePreference) {
        runCatching {
            defaults.setObject(AppJson.encodeToString(preference), forKey = PREFERENCE_KEY)
        }
    }
}

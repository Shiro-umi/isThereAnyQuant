package org.shiroumi.quant_kmp.ui.theme

import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import org.shiroumi.quant_kmp.AppJson
import org.w3c.dom.get
import org.w3c.dom.set

actual object ThemePreferenceStore {

    private const val PREFERENCE_KEY = "quant.theme.preference"

    actual suspend fun load(): ThemePreference? {
        val encoded = runCatching { localStorage[PREFERENCE_KEY] }.getOrNull() ?: return null
        return runCatching { AppJson.decodeFromString<ThemePreference>(encoded) }.getOrNull()
    }

    actual suspend fun save(preference: ThemePreference) {
        runCatching { localStorage[PREFERENCE_KEY] = AppJson.encodeToString(preference) }
    }
}

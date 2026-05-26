package org.shiroumi.quant_kmp.ui.theme

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath
import org.shiroumi.quant_kmp.AppJson
import org.shiroumi.quant_kmp.applicationContext

actual object ThemePreferenceStore {

    private val preferenceKey = stringPreferencesKey("theme_preference")

    private val dataStore by lazy {
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                val context = requireNotNull(applicationContext) {
                    "Application context is not initialized"
                }
                context.filesDir.resolve("theme_preference.preferences_pb").absolutePath.toPath()
            }
        )
    }

    actual suspend fun load(): ThemePreference? {
        val encoded = dataStore.data.first()[preferenceKey] ?: return null
        return runCatching { AppJson.decodeFromString<ThemePreference>(encoded) }.getOrNull()
    }

    actual suspend fun save(preference: ThemePreference) {
        dataStore.edit { preferences ->
            preferences[preferenceKey] = AppJson.encodeToString(preference)
        }
    }
}

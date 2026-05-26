package org.shiroumi.quant_kmp.util

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.shiroumi.quant_kmp.AppJson
import org.shiroumi.quant_kmp.applicationContext
import okio.Path.Companion.toPath

actual object PlatformAuthSessionStore {

    actual val storesRefreshToken: Boolean = true

    private val sessionKey = stringPreferencesKey("auth_session")

    private val dataStore by lazy {
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                val context = requireNotNull(applicationContext) {
                    "Application context is not initialized"
                }
                context.filesDir.resolve("auth_session.preferences_pb").absolutePath.toPath()
            }
        )
    }

    actual suspend fun load(): AuthSession? {
        val encoded = dataStore.data.first()[sessionKey] ?: return null
        return runCatching { AppJson.decodeFromString<AuthSession>(encoded) }
            .getOrElse {
                clear()
                null
            }
    }

    actual suspend fun save(session: AuthSession) {
        dataStore.edit { preferences ->
            preferences[sessionKey] = AppJson.encodeToString(session)
        }
    }

    actual suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(sessionKey)
        }
    }
}

package org.shiroumi.quant_kmp.feature.strategytracking.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import org.shiroumi.quant_kmp.applicationContext

actual object TrackingCalibrationStore {

    private val preferenceKey = stringPreferencesKey("tracking_follow_start_date")

    private val dataStore by lazy {
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                val context = requireNotNull(applicationContext) {
                    "Application context is not initialized"
                }
                context.filesDir.resolve("tracking_calibration.preferences_pb").absolutePath.toPath()
            }
        )
    }

    actual suspend fun load(): String? =
        dataStore.data.first()[preferenceKey]

    actual suspend fun save(followStartDate: String?) {
        dataStore.edit { preferences ->
            preferences[preferenceKey] = followStartDate.orEmpty()
        }
    }
}

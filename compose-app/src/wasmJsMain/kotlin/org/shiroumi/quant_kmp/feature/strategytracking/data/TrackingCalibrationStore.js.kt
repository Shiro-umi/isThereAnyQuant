package org.shiroumi.quant_kmp.feature.strategytracking.data

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

actual object TrackingCalibrationStore {

    private const val PREFERENCE_KEY = "quant.tracking.followStartDate"

    actual suspend fun load(): String? =
        runCatching { localStorage[PREFERENCE_KEY] }.getOrNull()

    actual suspend fun save(followStartDate: String?) {
        runCatching { localStorage[PREFERENCE_KEY] = followStartDate.orEmpty() }
    }
}

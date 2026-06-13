package org.shiroumi.quant_kmp.feature.strategytracking.data

import platform.Foundation.NSUserDefaults

/**
 * iOS 平台的最早跟随日校准存储。
 *
 * 与主题偏好、登录会话同样落在 [NSUserDefaults]：app 内长期声明型偏好统一走系统级
 * key-value 持久化，跨会话自动恢复。
 *
 * NSUserDefaults 在 key 不存在时 stringForKey 返回 null，恰好对应「从未设置」语义；
 * 存空串表示「显式清除校准（跟随全程）」，二者可区分。
 */
actual object TrackingCalibrationStore {

    private const val PREFERENCE_KEY = "quant.tracking.followStartDate"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual suspend fun load(): String? =
        defaults.stringForKey(PREFERENCE_KEY)

    actual suspend fun save(followStartDate: String?) {
        defaults.setObject(followStartDate.orEmpty(), forKey = PREFERENCE_KEY)
    }
}

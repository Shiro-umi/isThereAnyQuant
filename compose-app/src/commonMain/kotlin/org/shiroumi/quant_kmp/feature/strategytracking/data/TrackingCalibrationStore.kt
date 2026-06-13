package org.shiroumi.quant_kmp.feature.strategytracking.data

/**
 * 最早跟随日校准偏好的跨平台持久化存储。
 *
 * 校准是用户对「自己从哪天开始跟随」的长期声明，而非一次性临时视图，
 * 因此跨会话持久化：下次打开跟踪页自动恢复上次选定的跟随起始日。
 *
 * - Android：DataStore preferences
 * - Web / Wasm：localStorage
 *
 * 存空字符串语义上等于「已清除校准（跟随全程）」；[load] 返回 null 表示从未设置过偏好。
 */
expect object TrackingCalibrationStore {
    /** 加载持久化的跟随起始日；null = 从未设置；空串 = 显式清除（跟随全程）。 */
    suspend fun load(): String?

    /** 持久化跟随起始日；传 null 清除偏好（回到跟随全程）。 */
    suspend fun save(followStartDate: String?)
}

package org.shiroumi.quant_kmp.platform

/**
 * 判断当前是否运行在 Android 平台
 */
expect fun isAndroidPlatform(): Boolean

/**
 * 判断当前是否运行在 Web（WasmJS）平台。
 * 客户端下载入口仅在 Web 端展示，原生 Android / iOS 端隐藏。
 */
expect fun isWebPlatform(): Boolean

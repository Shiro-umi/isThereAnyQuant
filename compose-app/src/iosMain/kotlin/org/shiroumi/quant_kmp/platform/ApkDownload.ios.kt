package org.shiroumi.quant_kmp.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS 没有 APK side-load 概念（应用经 App Store 分发）。
 * 这里把下载入口降级为用系统浏览器打开链接，保留"用户点击下载后有反馈"的业务意图。
 * 实际上 isAndroidPlatform() 在 iOS 返回 false，前端通常不会暴露 APK 下载入口。
 */
actual fun downloadApk(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    val application = UIApplication.sharedApplication
    if (application.canOpenURL(nsUrl)) {
        application.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

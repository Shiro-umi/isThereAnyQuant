package org.shiroumi.quant_kmp.platform

import platform.UIKit.UIPasteboard

/**
 * iOS 剪贴板写入：通过 UIPasteboard.generalPasteboard 写入字符串。
 * 失败不抛异常，返回 false 让上层走 fallback。
 */
actual suspend fun copyToClipboard(text: String): Boolean {
    return runCatching {
        UIPasteboard.generalPasteboard.string = text
        true
    }.getOrDefault(false)
}

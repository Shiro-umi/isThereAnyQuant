@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp.platform

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

private fun writeClipboardText(text: String): Promise<JsAny?> = js(
    "window.navigator.clipboard ? window.navigator.clipboard.writeText(text) : Promise.reject()"
)

actual suspend fun copyToClipboard(text: String): Boolean {
    return runCatching {
        writeClipboardText(text).await<JsAny?>()
        true
    }.getOrElse {
        // 兜底：用 prompt 让用户手动复制（debug 环境 http 也能用）
        runCatching {
            window.prompt("复制此链接：", text)
            true
        }.getOrDefault(false)
    }
}

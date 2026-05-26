package org.shiroumi.quant_kmp.platform

/**
 * 跨平台剪贴板写入。
 *
 * - WasmJS：调用 `navigator.clipboard.writeText`（需要 https 或 localhost）
 * - Android：通过 ClipboardManager 写入 ClipData
 * - JVM Desktop：写入 system clipboard
 *
 * 任何一个平台失败都不抛异常，返回 false 让上层走 fallback（如 Snackbar 显示链接让用户手动复制）。
 */
expect suspend fun copyToClipboard(text: String): Boolean

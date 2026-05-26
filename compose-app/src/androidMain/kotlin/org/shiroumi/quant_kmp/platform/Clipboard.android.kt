package org.shiroumi.quant_kmp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.shiroumi.quant_kmp.applicationContext

actual suspend fun copyToClipboard(text: String): Boolean {
    val context = applicationContext ?: return false
    return runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("share", text))
        true
    }.getOrDefault(false)
}

package org.shiroumi.quant_kmp.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual suspend fun copyToClipboard(text: String): Boolean {
    return runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)
}

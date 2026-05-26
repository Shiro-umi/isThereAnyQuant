package org.shiroumi.quant_kmp.platform

import kotlinx.browser.document

actual fun downloadApk(url: String) {
    val anchor = document.createElement("a") as org.w3c.dom.HTMLAnchorElement
    anchor.href = url
    anchor.download = "quant-app.apk"
    anchor.style.display = "none"
    document.body?.appendChild(anchor)
    anchor.click()
    document.body?.removeChild(anchor)
}

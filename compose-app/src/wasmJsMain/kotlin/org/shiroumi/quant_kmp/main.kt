@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.shiroumi.quant_kmp.ui.auth.AuthGate
import org.shiroumi.quant_kmp.ui.theme.AppTheme
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import kotlin.JsFun

@JsFun(
    """(delay) => setTimeout(() => {
        if (typeof window.__quantAppReady === 'function') {
            window.__quantAppReady();
        }
    }, delay)"""
)
private external fun notifyQuantAppReadyAfter(delayMillis: Int)

fun setupHiDPICanvas() {
    val canvas = document.getElementById("compose-root") as? HTMLCanvasElement ?: return

    fun syncSize() {
        val dpr = window.devicePixelRatio
        val cssW = canvas.clientWidth.takeIf { it > 0 } ?: window.innerWidth
        val cssH = canvas.clientHeight.takeIf { it > 0 } ?: window.innerHeight

        val pw = (cssW * dpr).toInt()
        val ph = (cssH * dpr).toInt()

        if (canvas.width != pw || canvas.height != ph) {
            canvas.width  = pw
            canvas.height = ph
        }
    }

    window.addEventListener("resize", { _: Event -> syncSize() })
    syncSize()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    setupHiDPICanvas()
    ComposeViewport(viewportContainerId = "compose-root") {
        AppTheme {
            AuthGate()
        }
    }
    // 通知 HTML Loading 层：Compose 已就绪，可以淡出并清理
    notifyQuantAppReadyAfter(300)
}

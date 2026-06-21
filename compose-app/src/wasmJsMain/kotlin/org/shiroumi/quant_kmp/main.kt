@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.shiroumi.quant_kmp.ui.auth.AuthGate
import org.shiroumi.quant_kmp.ui.theme.AppTheme
import kotlin.JsFun

@JsFun(
    """(delay) => setTimeout(() => {
        if (typeof window.__quantAppReady === 'function') {
            window.__quantAppReady();
        }
    }, delay)"""
)
private external fun notifyQuantAppReadyAfter(delayMillis: Int)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // HiDPI / devicePixelRatio 由 ComposeViewport 内部自动处理，无需手动改写 canvas 尺寸。
    // 手动改写 canvas.width/height 属于已废弃的 CanvasBasedWindow 模型，会与 ComposeViewport
    // 内部 resize 冲突，破坏隐藏输入框定位，导致移动端软键盘掉焦。
    ComposeViewport(viewportContainerId = "compose-root") {
        AppTheme {
            AuthGate()
        }
    }
    // 通知 HTML Loading 层：Compose 已就绪，可以淡出并清理
    notifyQuantAppReadyAfter(300)
}

package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import kotlin.js.ExperimentalWasmJsInterop
import org.w3c.dom.events.Event

/**
 * 读取页面是否处于隐藏态。
 *
 * Wasm 的 DOM 绑定不暴露 [document].visibilityState，用 Page Visibility API
 * 的 `document.hidden` 直接判断，避免依赖未绑定的属性。
 */
private fun isDocumentHidden(): Boolean = js("document.hidden")

/**
 * Web 平台生命周期。
 *
 * 浏览器没有 Android 那样的细粒度生命周期，用标准 Visibility API 的页面可见性映射前后台：
 * - 切回标签页 / 恢复窗口（visible）→ onResume → 重连 WebSocket
 * - 切走标签页 / 最小化（hidden）→ onPause → 断开 WebSocket
 *
 * onStart/onStop 在浏览器上无法准确区分，忽略；与 jvm 实现保持同一约定。
 */
@OptIn(ExperimentalWasmJsInterop::class)
@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            if (isDocumentHidden()) {
                onPause()
                LifecycleManager.dispatchEvent(LifecycleEvent.ON_PAUSE)
            } else {
                onResume()
                LifecycleManager.dispatchEvent(LifecycleEvent.ON_RESUME)
            }
        }
        document.addEventListener("visibilitychange", listener)
        onDispose {
            document.removeEventListener("visibilitychange", listener)
        }
    }
}

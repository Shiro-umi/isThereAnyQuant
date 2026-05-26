package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop
import org.w3c.dom.events.Event

@OptIn(ExperimentalWasmJsInterop::class)
@Composable
actual fun PlatformBrowserBackHandler(
    enabled: Boolean,
    backStackDepth: Int,
    onBack: () -> Boolean,
) {
    val latestOnBack = rememberUpdatedState(onBack)
    val trackedDepth = remember { mutableIntStateOf(0) }
    val suppressPopCount = remember { mutableIntStateOf(0) }
    val handlingBrowserPop = remember { mutableStateOf(false) }

    LaunchedEffect(enabled, backStackDepth) {
        val targetDepth = if (enabled) backStackDepth.coerceAtLeast(0) else 0
        val delta = targetDepth - trackedDepth.intValue

        when {
            delta > 0 -> repeat(delta) {
                window.history.pushState(null, "", window.location.href)
            }
            delta < 0 && !handlingBrowserPop.value -> {
                suppressPopCount.intValue += -delta
                window.history.go(delta)
            }
        }

        trackedDepth.intValue = targetDepth
        handlingBrowserPop.value = false
    }

    DisposableEffect(enabled) {
        if (!enabled) {
            trackedDepth.intValue = 0
            suppressPopCount.intValue = 0
            handlingBrowserPop.value = false
            onDispose {}
        } else {
            val listener: (Event) -> Unit = {
                if (suppressPopCount.intValue > 0) {
                    suppressPopCount.intValue -= 1
                } else {
                    handlingBrowserPop.value = true
                    if (!latestOnBack.value()) {
                        handlingBrowserPop.value = false
                    }
                }
            }
            window.addEventListener("popstate", listener)
            onDispose {
                window.removeEventListener("popstate", listener)
            }
        }
    }
}

package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * iOS 平台生命周期观察者。
 *
 * Compose Multiplatform 在 iOS 上把 app 前后台切换（applicationDidBecomeActive /
 * willResignActive 等）桥接到 [LocalLifecycleOwner] 的 Lifecycle，这里以与 Android actual
 * 同构的方式监听并 dispatch 到全局 [LifecycleManager]，让 GlobalWebSocketClient 的
 * resume/pause 连接策略在 iOS 上同样生效。
 */
@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    onResume()
                    LifecycleManager.dispatchEvent(LifecycleEvent.ON_RESUME)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    onPause()
                    LifecycleManager.dispatchEvent(LifecycleEvent.ON_PAUSE)
                }
                Lifecycle.Event.ON_START -> {
                    onStart()
                    LifecycleManager.dispatchEvent(LifecycleEvent.ON_START)
                }
                Lifecycle.Event.ON_STOP -> {
                    onStop()
                    LifecycleManager.dispatchEvent(LifecycleEvent.ON_STOP)
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

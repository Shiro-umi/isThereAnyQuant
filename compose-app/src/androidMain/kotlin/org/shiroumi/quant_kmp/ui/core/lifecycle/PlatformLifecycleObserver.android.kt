package org.shiroumi.quant_kmp.ui.core.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
actual fun PlatformLifecycleEffect(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    
    DisposableEffect(context) {
        val lifecycleOwner = context as? LifecycleOwner
        
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
        
        lifecycleOwner?.lifecycle?.addObserver(observer)
        
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }
}
